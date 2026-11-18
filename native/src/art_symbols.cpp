#include "art_symbols.hpp"

#include <algorithm>
#include <bit>
#include <cerrno>
#include <cstring>
#include <elf.h>
#include <fcntl.h>
#include <limits>
#include <link.h>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

namespace haaru {
namespace {

using SymbolIndex = std::map<std::string, std::uintptr_t, std::less<>>;
using Bytes = std::vector<unsigned char>;

struct LoadRange {
    std::uintptr_t begin;
    std::uintptr_t end;
    std::uint32_t flags;
};

struct ArtImage {
    std::string path;
    std::uintptr_t bias;
    std::vector<LoadRange> loads;
};

struct Images {
    std::vector<ArtImage> values;
    bool failed = false;
};

class ReadOnlyFd final {
public:
    explicit ReadOnlyFd(const char* path)
        : fd_(::open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK)) {}
    ~ReadOnlyFd() { if (fd_ >= 0) ::close(fd_); }
    ReadOnlyFd(const ReadOnlyFd&) = delete;
    ReadOnlyFd& operator=(const ReadOnlyFd&) = delete;
    [[nodiscard]] int get() const { return fd_; }
private:
    int fd_;
};

[[nodiscard]] bool add(std::uint64_t a, std::uint64_t b, std::uint64_t& result) {
    if (b > std::numeric_limits<std::uint64_t>::max() - a) return false;
    result = a + b;
    return true;
}

[[nodiscard]] bool multiply(std::uint64_t a, std::uint64_t b, std::uint64_t& result) {
    if (a != 0 && b > std::numeric_limits<std::uint64_t>::max() / a) return false;
    result = a * b;
    return true;
}

[[nodiscard]] bool contains(const Bytes& data, std::uint64_t offset, std::uint64_t size) {
    return offset <= data.size() && size <= data.size() - offset;
}

template <typename T>
[[nodiscard]] bool read(const Bytes& data, std::uint64_t offset, T& result) {
    if (!contains(data, offset, sizeof(T))) return false;
    // ELF offsets need not have the alignment of a C++ struct.
    std::memcpy(&result, data.data() + static_cast<std::size_t>(offset), sizeof(T));
    return true;
}

[[nodiscard]] bool table(const Bytes& data, std::uint64_t offset,
                         std::uint64_t stride, std::uint64_t count,
                         std::size_t minimum_stride) {
    std::uint64_t size;
    return stride >= minimum_stride && multiply(stride, count, size)
           && contains(data, offset, size);
}

template <typename T>
[[nodiscard]] bool entry(const Bytes& data, std::uint64_t offset,
                         std::uint64_t stride, std::uint64_t index, T& result) {
    std::uint64_t displacement, position;
    return stride >= sizeof(T) && multiply(stride, index, displacement)
           && add(offset, displacement, position) && read(data, position, result);
}

[[nodiscard]] bool power_of_two(std::uint64_t value) {
    return value == 0 || (value & (value - 1)) == 0;
}

int collect_image(dl_phdr_info* info, std::size_t, void* opaque) {
    auto& images = *static_cast<Images*>(opaque);
    if (!info || !info->dlpi_name || !info->dlpi_phdr) return 0;
    const std::string_view path(info->dlpi_name);
    const auto slash = path.find_last_of('/');
    const auto basename = slash == path.npos ? path : path.substr(slash + 1);
    if (basename != "libart.so" && basename != "libartd.so") return 0;

    try {
        ArtImage image{std::string(path), static_cast<std::uintptr_t>(info->dlpi_addr), {}};
        for (std::size_t i = 0; i < info->dlpi_phnum; ++i) {
            const auto& header = info->dlpi_phdr[i];
            if (header.p_type != PT_LOAD || header.p_memsz == 0) continue;
            std::uint64_t begin, end;
            if (!add(image.bias, header.p_vaddr, begin)
                || !add(begin, header.p_memsz, end)
                || end > std::numeric_limits<std::uintptr_t>::max()) return 0;
            image.loads.push_back({static_cast<std::uintptr_t>(begin),
                                   static_cast<std::uintptr_t>(end), header.p_flags});
        }
        if (!image.loads.empty()) images.values.push_back(std::move(image));
    } catch (...) {
        // Never unwind a C++ allocation failure through dl_iterate_phdr.
        images.failed = true;
        return 1;
    }
    return 0;
}

[[nodiscard]] bool read_file(const std::string& path, Bytes& data) {
    ReadOnlyFd file(path.c_str());
    if (file.get() < 0) return false;
    struct stat status{};
    if (::fstat(file.get(), &status) != 0 || !S_ISREG(status.st_mode)
        || status.st_size < 0) return false;
    const auto size = static_cast<std::uint64_t>(status.st_size);
    if (size < EI_NIDENT || size > std::numeric_limits<std::size_t>::max()
        || size > static_cast<std::uint64_t>(std::numeric_limits<off_t>::max())
        || size > data.max_size()) return false;
    data.resize(static_cast<std::size_t>(size));
    std::size_t position = 0;
    while (position < data.size()) {
        const auto count = std::min(data.size() - position,
                                   static_cast<std::size_t>(std::numeric_limits<ssize_t>::max()));
        const auto got = ::pread(file.get(), data.data() + position, count,
                                 static_cast<off_t>(position));
        if (got < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (got == 0) return false;
        position += static_cast<std::size_t>(got);
    }
    return true;
}

[[nodiscard]] bool mapped(const ArtImage& image, std::uint64_t value,
                          std::uint64_t size, bool executable, std::uintptr_t& address) {
    std::uint64_t location, end;
    // Even a zero-size symbol must name a byte in a loaded segment, not its end.
    if (!add(image.bias, value, location) || !add(location, std::max(size, std::uint64_t{1}), end)
        || end > std::numeric_limits<std::uintptr_t>::max()) return false;
    for (const auto& range : image.loads) {
        if (location >= range.begin && end <= range.end
            && (!executable || (range.flags & PF_X) != 0)) {
            address = static_cast<std::uintptr_t>(location);
            return true;
        }
    }
    return false;
}

template <typename Ehdr, typename Shdr, typename Sym, typename Phdr>
[[nodiscard]] bool parse(const Bytes& data, const ArtImage& image, SymbolIndex& symbols) {
    Ehdr header{};
    if (!read(data, 0, header) || header.e_version != EV_CURRENT
        || (header.e_type != ET_DYN && header.e_type != ET_EXEC)
        || header.e_ehsize < sizeof(Ehdr) || !contains(data, 0, header.e_ehsize)
        || header.e_shoff == 0 || header.e_shentsize < sizeof(Shdr)) return false;

    Shdr zero{};
    if (!read(data, header.e_shoff, zero) || zero.sh_type != SHT_NULL) return false;
    const std::uint64_t section_count = header.e_shnum == 0 ? zero.sh_size : header.e_shnum;
    if (section_count == 0 || !table(data, header.e_shoff, header.e_shentsize,
                                    section_count, sizeof(Shdr))) return false;
    const std::uint64_t program_count = header.e_phnum == PN_XNUM ? zero.sh_info : header.e_phnum;
    if (program_count == 0 || !table(data, header.e_phoff, header.e_phentsize,
                                    program_count, sizeof(Phdr))) return false;

    for (std::uint64_t i = 0; i < program_count; ++i) {
        Phdr program{};
        if (!entry(data, header.e_phoff, header.e_phentsize, i, program)) return false;
        if (program.p_type == PT_NULL) continue;
        if (!contains(data, program.p_offset, program.p_filesz)) return false;
        if (program.p_type == PT_LOAD) {
            std::uint64_t end;
            if (program.p_filesz > program.p_memsz || !power_of_two(program.p_align)
                || !add(program.p_vaddr, program.p_memsz, end)) return false;
        }
    }

    std::vector<Shdr> sections;
    if (section_count > sections.max_size()) return false;
    sections.reserve(static_cast<std::size_t>(section_count));
    for (std::uint64_t i = 0; i < section_count; ++i) {
        Shdr section{};
        if (!entry(data, header.e_shoff, header.e_shentsize, i, section)
            || !power_of_two(section.sh_addralign)) return false;
        // sh_size in section zero can hold the extended section count.
        if (section.sh_type != SHT_NULL && section.sh_type != SHT_NOBITS
            && !contains(data, section.sh_offset, section.sh_size)) return false;
        std::uint64_t end;
        if ((section.sh_flags & SHF_ALLOC) != 0
            && !add(section.sh_addr, section.sh_size, end)) return false;
        sections.push_back(section);
    }
    const std::uint64_t names_index = header.e_shstrndx == SHN_XINDEX
                                      ? zero.sh_link : header.e_shstrndx;
    if (names_index != SHN_UNDEF
        && (names_index >= section_count || sections[names_index].sh_type != SHT_STRTAB)) return false;

    bool found_table = false;
    // Prefer dynamic definitions when a name also appears in the full table.
    for (const auto wanted_type : {SHT_DYNSYM, SHT_SYMTAB}) {
        for (std::size_t index = 0; index < sections.size(); ++index) {
            const auto& section = sections[index];
            if (section.sh_type != static_cast<std::uint32_t>(wanted_type)) continue;
            if (section.sh_entsize < sizeof(Sym) || section.sh_size % section.sh_entsize != 0
                || section.sh_link >= section_count) return false;
            const auto count = section.sh_size / section.sh_entsize;
            if (!table(data, section.sh_offset, section.sh_entsize, count, sizeof(Sym))
                || section.sh_info > count) return false;
            const auto& strings = sections[section.sh_link];
            if (strings.sh_type != SHT_STRTAB || strings.sh_size == 0
                || data[static_cast<std::size_t>(strings.sh_offset)] != '\0') return false;

            const Shdr* extended = nullptr;
            for (const auto& candidate : sections) {
                if (candidate.sh_type != SHT_SYMTAB_SHNDX || candidate.sh_link != index) continue;
                if (extended != nullptr || candidate.sh_entsize < sizeof(Elf32_Word)
                    || candidate.sh_size % candidate.sh_entsize != 0
                    || candidate.sh_size / candidate.sh_entsize != count
                    || !table(data, candidate.sh_offset, candidate.sh_entsize,
                              count, sizeof(Elf32_Word))) return false;
                extended = &candidate;
            }
            found_table = true;
            for (std::uint64_t i = 0; i < count; ++i) {
                Sym symbol{};
                if (!entry(data, section.sh_offset, section.sh_entsize, i, symbol)
                    || symbol.st_name >= strings.sh_size) return false;
                std::uint64_t name_offset;
                if (!add(strings.sh_offset, symbol.st_name, name_offset)) return false;
                const auto* name = reinterpret_cast<const char*>(data.data() + name_offset);
                const auto remaining = static_cast<std::size_t>(strings.sh_size - symbol.st_name);
                const auto* end = static_cast<const char*>(std::memchr(name, '\0', remaining));
                if (!end) return false;
                if (name == end || symbol.st_shndx == SHN_UNDEF) continue;

                std::uint64_t owner = symbol.st_shndx;
                if (owner == SHN_XINDEX) {
                    Elf32_Word value{};
                    if (!extended || !entry(data, extended->sh_offset, extended->sh_entsize, i, value)) return false;
                    owner = value;
                    if (owner == SHN_UNDEF) continue;
                } else if (owner >= SHN_LORESERVE) {
                    // ABS/COMMON do not identify an allocated section in this image.
                    continue;
                }
                if (owner >= section_count) return false;
                const auto& allocated = sections[owner];
                if ((allocated.sh_flags & SHF_ALLOC) == 0) continue;
                const auto type = ELF64_ST_TYPE(symbol.st_info);
                // Include data such as art::Runtime::instance_; TLS offsets and
                // IFUNC resolvers are not ordinary relocated object/function pointers.
                if (type != STT_FUNC && type != STT_OBJECT && type != STT_NOTYPE) continue;
                auto value = static_cast<std::uint64_t>(symbol.st_value);
                const bool thumb = header.e_machine == EM_ARM && type == STT_FUNC && (value & 1) != 0;
                const auto location = thumb ? value & ~std::uint64_t{1} : value;
                if (location < allocated.sh_addr
                    || location - allocated.sh_addr >= allocated.sh_size
                    || symbol.st_size > allocated.sh_size - (location - allocated.sh_addr)) continue;
                std::uintptr_t address;
                if (!mapped(image, location, symbol.st_size, type == STT_FUNC, address)) continue;
                if (thumb) address |= std::uintptr_t{1};
                symbols.try_emplace(std::string(name, static_cast<std::size_t>(end - name)), address);
            }
        }
    }
    return found_table && !symbols.empty();
}

[[nodiscard]] bool parse_image(const ArtImage& image, SymbolIndex& result) {
    Bytes data;
    if (!read_file(image.path, data) || std::memcmp(data.data(), ELFMAG, SELFMAG) != 0
        || data[EI_VERSION] != EV_CURRENT) return false;
    constexpr unsigned char encoding = std::endian::native == std::endian::little
                                        ? ELFDATA2LSB : ELFDATA2MSB;
    if (data[EI_DATA] != encoding) return false;
    if (data[EI_CLASS] == ELFCLASS64 && sizeof(void*) == 8)
        return parse<Elf64_Ehdr, Elf64_Shdr, Elf64_Sym, Elf64_Phdr>(data, image, result);
    if (data[EI_CLASS] == ELFCLASS32 && sizeof(void*) == 4)
        return parse<Elf32_Ehdr, Elf32_Shdr, Elf32_Sym, Elf32_Phdr>(data, image, result);
    return false;
}

} // namespace

bool ArtSymbols::initialize() {
    if (initialized_) return true;
    try {
        Images images;
        ::dl_iterate_phdr(collect_image, &images);
        if (images.failed) { error_ = "Cannot enumerate the loaded ART image"; return false; }
        if (images.values.empty()) { error_ = "No loaded libart.so or libartd.so image was found"; return false; }
        for (const auto& image : images.values) {
            SymbolIndex parsed;
            if (!parse_image(image, parsed)) { error_ = "Cannot read a usable ART ELF symbol table: " + image.path; continue; }
            symbols_.swap(parsed);
            initialized_ = true;
            error_.clear();
            return true;
        }
    } catch (const std::exception& failure) {
        error_ = std::string("ART symbol resolver failed: ") + failure.what();
    } catch (...) {
        // Initialization fails closed; never publish a partially parsed index.
        error_ = "Unknown ART symbol resolver failure";
    }
    return false;
}

void* ArtSymbols::exact(std::string_view name) const {
    if (!initialized_ || name.empty()) return nullptr;
    const auto found = symbols_.find(name);
    return found == symbols_.end() ? nullptr : reinterpret_cast<void*>(found->second);
}

void* ArtSymbols::prefix(std::string_view name) const {
    if (!initialized_ || name.empty()) return nullptr;
    const auto found = symbols_.lower_bound(name);
    return found == symbols_.end() || !std::string_view(found->first).starts_with(name)
           ? nullptr : reinterpret_cast<void*>(found->second);
}

} // namespace haaru
