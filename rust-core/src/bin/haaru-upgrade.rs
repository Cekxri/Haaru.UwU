use std::path::Path;

fn run() -> Result<serde_json::Value, String> {
    let arguments: Vec<_> = std::env::args_os().skip(1).collect();
    #[cfg(not(target_os = "android"))]
    {
        if arguments.len() != 3 || arguments[0] != "--fixture" { return Err("Host migration requires --fixture SOURCE DESTINATION".into()); }
        haaru_core::migration::migrate(Path::new(&arguments[1]), Path::new(&arguments[2]))
    }
    #[cfg(target_os = "android")]
    {
        if unsafe { libc::getuid() } != 0 { return Err("Root UID 0 required".into()); }
        if arguments.len() != 2 || Path::new(&arguments[0]) != Path::new("/data/adb/modules/haaru")
                || Path::new(&arguments[1]) != Path::new("/data/adb/modules_update/haaru") {
            return Err("Upgrade requires the installed and staged Haaru module directories".into());
        }
        haaru_core::migration::migrate(Path::new(&arguments[0]), Path::new(&arguments[1]))
    }
}

fn main() {
    match run() {
        Ok(result) => println!("{result}"),
        Err(error) => { println!("{}", serde_json::json!({"ok": false, "verified": false, "error": error})); std::process::exit(2); }
    }
}
