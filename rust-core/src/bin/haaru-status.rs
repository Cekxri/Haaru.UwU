use std::path::Path;
fn run() -> Result<(), String> {
    let arguments: Vec<_> = std::env::args_os().skip(1).collect();
    #[cfg(target_os = "android")]
    if unsafe { libc::getuid() } != 0 { return Err("Root UID 0 required".into()); }
    if arguments.len() != 2 || arguments[0] != "--watch" { return Err("Expected --watch MODULE_DIRECTORY".into()); }
    let module = Path::new(&arguments[1]);
    #[cfg(target_os = "android")]
    if module != Path::new("/data/adb/modules/haaru") { return Err("Unexpected module directory".into()); }
    haaru_core::observer::watch(module)
}
fn main() {
    if let Err(error) = run() {
        eprintln!("Haaru card observer: {error}");
        std::process::exit(2);
    }
}
