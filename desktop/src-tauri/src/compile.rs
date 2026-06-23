use crate::binaries;
use std::process::Command;

/// Compile C++ source to WASM using clang.
/// Returns the compiled .wasm bytes, or a detailed error with compiler output.
#[tauri::command]
pub fn compile_wasm(source: String) -> Result<Vec<u8>, String> {
    let tmp_dir = std::env::temp_dir().join("barf_wasm");
    std::fs::create_dir_all(&tmp_dir).map_err(|e| e.to_string())?;

    let src_path = tmp_dir.join("source.cpp");
    let out_path = tmp_dir.join("out.wasm");
    std::fs::write(&src_path, &source).map_err(|e| e.to_string())?;

    let output = Command::new("clang")
        .args([
            "--target=wasm32",
            "-O3",
            "-nostdlib",
            "-Wl,--no-entry",
            "-Wl,--export=setup",
            "-Wl,--export=on_frame",
            "-o",
        ])
        .arg(&out_path)
        .arg(&src_path)
        .output()
        .map_err(|e| format!("clang not found on PATH: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("Compilation failed:\n{}", stderr));
    }

    std::fs::read(&out_path).map_err(|e| e.to_string())
}

/// Compile Arduino sketch for ESP32 using arduino-cli.
/// Returns the compiled .bin path, or a detailed error with compiler output.
/// NOTE: arduino-cli requires the sketch folder name to match the .ino filename.
#[tauri::command]
pub fn compile_esp32(source: String) -> Result<String, String> {
    let tmp_dir = std::env::temp_dir().join("barf_esp32");
    std::fs::create_dir_all(&tmp_dir).map_err(|e| e.to_string())?;

    // Folder name "barf_esp32" must match sketch name "barf_esp32.ino"
    let sketch_path = tmp_dir.join("barf_esp32.ino");
    std::fs::write(&sketch_path, &source).map_err(|e| e.to_string())?;

    let output = binaries::command("arduino-cli")
        .args(["compile", "--fqbn", "esp32:esp32:esp32"])
        .arg(&tmp_dir)
        .output()
        .map_err(|e| format!("arduino-cli not found on PATH: {}", e))?;

    if !output.status.success() {
        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("Compilation failed:\n{}{}", stdout, stderr));
    }

    Ok(tmp_dir.join("barf_esp32.ino.bin").to_string_lossy().to_string())
}

/// Flash the last compiled ESP32 binary via arduino-cli upload.
#[tauri::command]
pub fn flash_esp32(port: String) -> Result<String, String> {
    let tmp_dir = std::env::temp_dir().join("barf_esp32");
    let bin_path = tmp_dir.join("barf_esp32.ino.bin");

    if !bin_path.exists() {
        return Err("No compiled firmware found — compile first.".to_string());
    }

    let output = binaries::command("arduino-cli")
        .args(["upload", "--fqbn", "esp32:esp32:esp32", "--port"])
        .arg(&port)
        .arg(&tmp_dir)
        .output()
        .map_err(|e| format!("arduino-cli not found on PATH: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("Flash failed:\n{}", stderr));
    }

    Ok("Flashed successfully".to_string())
}

/// List connected serial ports via arduino-cli board list.
#[tauri::command]
pub fn list_serial_ports() -> Result<Vec<String>, String> {
    let output = binaries::command("arduino-cli")
        .args(["board", "list", "--format", "json"])
        .output()
        .map_err(|e| format!("arduino-cli not found: {}", e))?;

    if !output.status.success() {
        return Ok(vec![]);
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let parsed: serde_json::Value =
        serde_json::from_str(&stdout).map_err(|e| format!("Failed to parse board list: {}", e))?;

    let ports = parsed
        .as_array()
        .map(|arr| {
            arr.iter()
                .filter_map(|item| {
                    item.get("port")
                        .and_then(|p| p.get("address"))
                        .and_then(|a| a.as_str())
                        .map(|s| s.to_string())
                })
                .collect()
        })
        .unwrap_or_default();

    Ok(ports)
}
