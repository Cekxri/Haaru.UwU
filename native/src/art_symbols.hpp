#pragma once

#include <cstdint>
#include <functional>
#include <map>
#include <string>
#include <string_view>

namespace haaru {

// Initialise before publishing the resolver to LSPlant. After initialisation,
// lookups only read this object's immutable symbol index.
class ArtSymbols final {
public:
    [[nodiscard]] bool initialize();
    [[nodiscard]] void* exact(std::string_view name) const;
    [[nodiscard]] void* prefix(std::string_view name) const;
    [[nodiscard]] const std::string& error() const { return error_; }

private:
    std::map<std::string, std::uintptr_t, std::less<>> symbols_;
    bool initialized_ = false;
    std::string error_;
};

} // namespace haaru
