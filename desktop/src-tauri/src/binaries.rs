use std::path::{Path, PathBuf};
use std::process::Command;

fn exe_suffix() -> &'static str {
    if cfg!(target_os = "windows") { ".exe" } else { "" }
}

fn probe_path(base: &Path, name: &str) -> Option<PathBuf> {
    let candidate = base.join(format!("{}{}", name, exe_suffix()));
    if candidate.exists() { Some(candidate) } else { None }
}

fn find_binary(name: &str) -> Option<PathBuf> {
    // 1. Check next to the running executable (production bundle)
    if let Ok(exe) = std::env::current_exe() {
        if let Some(exe_dir) = exe.parent() {
            if let Some(found) = probe_path(exe_dir, name) {
                return Some(found);
            }
            // 2. Check binaries/ subdirectory (Tauri 2 Windows resource bundling)
            let bin_subdir = exe_dir.join("binaries");
            if let Some(found) = probe_path(&bin_subdir, name) {
                return Some(found);
            }
            // 3. Check ../binaries/ (when running from src-tauri/)
            if let Some(parent) = exe_dir.parent() {
                let bin_dir = parent.join("binaries");
                if let Some(found) = probe_path(&bin_dir, name) {
                    return Some(found);
                }
            }
            // 4. Check resources/ subdirectory (macOS .app bundle layout)
            let resources = exe_dir.join("resources");
            if let Some(found) = probe_path(&resources, name) {
                return Some(found);
            }
            // 5. Check ../Resources/ (macOS bundle Contents/Resources)
            if let Some(parent) = exe_dir.parent() {
                let mac_resources = parent.join("Resources");
                if let Some(found) = probe_path(&mac_resources, name) {
                    return Some(found);
                }
            }
        }
    }

    // 6. Check development path: <project>/desktop/src-tauri/binaries/
    if let Ok(cwd) = std::env::current_dir() {
        let dev_path = cwd.join("binaries");
        if let Some(found) = probe_path(&dev_path, name) {
            return Some(found);
        }
    }

    None
}

/// Run a binary by trying the bundled location first, then falling back to PATH.
pub fn command(name: &str) -> Command {
    if let Some(path) = find_binary(name) {
        Command::new(path)
    } else {
        Command::new(name)
    }
}

/// Get the path to a bundled binary, if found.
pub fn path(name: &str) -> Option<PathBuf> {
    find_binary(name)
}
