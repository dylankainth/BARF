use crate::binaries;
use serde::{Deserialize, Serialize};
use std::process::Stdio;
use tauri::Emitter;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InstalledPlatform {
    pub id: String,
    pub installed: String,
    pub name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AvailablePlatform {
    pub id: String,
    pub latest: String,
    pub name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InstalledLibrary {
    pub name: String,
    pub version: String,
    pub location: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AvailableLibrary {
    pub name: String,
    pub latest: String,
    pub author: String,
    pub sentence: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct ProgressEvent {
    pub line: String,
    pub done: bool,
    pub error: bool,
}

// ─── Sync commands ────────────────────────────────────────────────────────────

#[tauri::command]
pub fn get_additional_urls() -> Result<Vec<String>, String> {
    let output = binaries::command("arduino-cli")
        .args(["config", "dump", "--format", "json"])
        .output()
        .map_err(|e| format!("Failed to run arduino-cli: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("arduino-cli error: {}", stderr));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let json: serde_json::Value =
        serde_json::from_str(&stdout).map_err(|e| format!("JSON parse error: {}", e))?;

    let urls = json
        .get("config")
        .and_then(|cfg| cfg.get("board_manager"))
        .and_then(|bm| bm.get("additional_urls"))
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|v| v.as_str().map(String::from))
                .collect()
        })
        .unwrap_or_default();

    Ok(urls)
}

#[tauri::command]
pub fn add_additional_url(url: String) -> Result<(), String> {
    let output = binaries::command("arduino-cli")
        .args(["config", "add", "board_manager.additional_urls", &url])
        .output()
        .map_err(|e| format!("Failed to run arduino-cli: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("arduino-cli error: {}", stderr));
    }

    Ok(())
}

#[tauri::command]
pub fn remove_additional_url(url: String) -> Result<(), String> {
    let output = binaries::command("arduino-cli")
        .args(["config", "remove", "board_manager.additional_urls", &url])
        .output()
        .map_err(|e| format!("Failed to run arduino-cli: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("arduino-cli error: {}", stderr));
    }

    Ok(())
}

#[tauri::command]
pub fn list_installed_platforms() -> Result<Vec<InstalledPlatform>, String> {
    let output = binaries::command("arduino-cli")
        .args(["core", "list", "--format", "json"])
        .output()
        .map_err(|e| format!("Failed to run arduino-cli: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("arduino-cli error: {}", stderr));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let stdout = stdout.trim();

    if stdout.is_empty() || stdout == "null" || stdout == "[]" {
        return Ok(vec![]);
    }

    // The output may be a JSON object with "platforms" key or a plain array
    let json: serde_json::Value =
        serde_json::from_str(stdout).map_err(|e| format!("JSON parse error: {}", e))?;

    let arr = if let Some(platforms) = json.get("platforms") {
        platforms.as_array().cloned().unwrap_or_default()
    } else if let Some(arr) = json.as_array() {
        arr.clone()
    } else {
        vec![]
    };

    let platforms = arr
        .into_iter()
        .filter_map(|v| serde_json::from_value::<InstalledPlatform>(v).ok())
        .collect();

    Ok(platforms)
}

#[tauri::command]
pub fn search_platforms(query: String) -> Result<Vec<AvailablePlatform>, String> {
    let output = binaries::command("arduino-cli")
        .args(["core", "search", &query, "--format", "json"])
        .output()
        .map_err(|e| format!("Failed to run arduino-cli: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("arduino-cli error: {}", stderr));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let stdout = stdout.trim();

    if stdout.is_empty() || stdout == "null" || stdout == "[]" {
        return Ok(vec![]);
    }

    let json: serde_json::Value =
        serde_json::from_str(stdout).map_err(|e| format!("JSON parse error: {}", e))?;

    let arr = if let Some(platforms) = json.get("platforms") {
        platforms.as_array().cloned().unwrap_or_default()
    } else if let Some(arr) = json.as_array() {
        arr.clone()
    } else {
        vec![]
    };

    let platforms = arr
        .into_iter()
        .filter_map(|v| serde_json::from_value::<AvailablePlatform>(v).ok())
        .collect();

    Ok(platforms)
}

#[tauri::command]
pub fn list_installed_libraries() -> Result<Vec<InstalledLibrary>, String> {
    let output = binaries::command("arduino-cli")
        .args(["lib", "list", "--format", "json"])
        .output()
        .map_err(|e| format!("Failed to run arduino-cli: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("arduino-cli error: {}", stderr));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let stdout = stdout.trim();

    if stdout.is_empty() || stdout == "null" {
        return Ok(vec![]);
    }

    let json: serde_json::Value =
        serde_json::from_str(stdout).map_err(|e| format!("JSON parse error: {}", e))?;

    let installed = match json.get("installed_libraries").and_then(|v| v.as_array()) {
        Some(arr) => arr.clone(),
        None => return Ok(vec![]),
    };

    let libraries = installed
        .into_iter()
        .filter_map(|entry| {
            let lib = entry.get("library")?;
            Some(InstalledLibrary {
                name: lib.get("name")?.as_str()?.to_string(),
                version: lib.get("version")?.as_str()?.to_string(),
                location: lib.get("location")?.as_str()?.to_string(),
            })
        })
        .collect();

    Ok(libraries)
}

#[tauri::command]
pub fn search_libraries(query: String) -> Result<Vec<AvailableLibrary>, String> {
    let output = binaries::command("arduino-cli")
        .args(["lib", "search", &query, "--format", "json"])
        .output()
        .map_err(|e| format!("Failed to run arduino-cli: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("arduino-cli error: {}", stderr));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let stdout = stdout.trim();

    if stdout.is_empty() || stdout == "null" {
        return Ok(vec![]);
    }

    let json: serde_json::Value =
        serde_json::from_str(stdout).map_err(|e| format!("JSON parse error: {}", e))?;

    let libraries_arr = match json.get("libraries").and_then(|v| v.as_array()) {
        Some(arr) => arr.clone(),
        None => return Ok(vec![]),
    };

    let libraries = libraries_arr
        .into_iter()
        .filter_map(|entry| {
            let name = entry.get("name")?.as_str()?.to_string();
            let latest = entry.get("latest")?;
            Some(AvailableLibrary {
                name,
                latest: latest.get("version")?.as_str()?.to_string(),
                author: latest
                    .get("author")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                sentence: latest
                    .get("sentence")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
            })
        })
        .collect();

    Ok(libraries)
}

// ─── Async streaming helper ───────────────────────────────────────────────────

async fn stream_command(
    mut cmd: tokio::process::Command,
    app: &tauri::AppHandle,
) -> Result<(), String> {
    use tokio::io::AsyncBufReadExt;

    cmd.stdout(Stdio::piped());
    cmd.stderr(Stdio::piped());

    let mut child = cmd.spawn().map_err(|e| e.to_string())?;

    let stdout = child.stdout.take().expect("stdout not captured");
    let stderr = child.stderr.take().expect("stderr not captured");

    let mut stdout_lines = tokio::io::BufReader::new(stdout).lines();
    let mut stderr_lines = tokio::io::BufReader::new(stderr).lines();

    let mut stdout_done = false;
    let mut stderr_done = false;

    loop {
        tokio::select! {
            line = stdout_lines.next_line(), if !stdout_done => {
                match line {
                    Ok(Some(l)) => {
                        app.emit("arduino-manager-progress", ProgressEvent {
                            line: l,
                            done: false,
                            error: false,
                        }).ok();
                    }
                    _ => { stdout_done = true; }
                }
            }
            line = stderr_lines.next_line(), if !stderr_done => {
                match line {
                    Ok(Some(l)) => {
                        app.emit("arduino-manager-progress", ProgressEvent {
                            line: l,
                            done: false,
                            error: false,
                        }).ok();
                    }
                    _ => { stderr_done = true; }
                }
            }
        }
        if stdout_done && stderr_done {
            break;
        }
    }

    let status = child.wait().await.map_err(|e| e.to_string())?;

    if !status.success() {
        app.emit("arduino-manager-progress", ProgressEvent {
            line: String::new(),
            done: true,
            error: true,
        }).ok();
        return Err("Command failed".into());
    }

    app.emit("arduino-manager-progress", ProgressEvent {
        line: String::new(),
        done: true,
        error: false,
    }).ok();

    Ok(())
}

// ─── Async streaming commands ─────────────────────────────────────────────────

#[tauri::command]
pub async fn install_platform(platform: String, app: tauri::AppHandle) -> Result<(), String> {
    let arduino_cli = binaries::path("arduino-cli").unwrap_or_else(|| "arduino-cli".into());
    let mut cmd = tokio::process::Command::new(arduino_cli);
    cmd.args(["core", "install", &platform]);
    stream_command(cmd, &app).await
}

#[tauri::command]
pub async fn uninstall_platform(platform: String, app: tauri::AppHandle) -> Result<(), String> {
    let arduino_cli = binaries::path("arduino-cli").unwrap_or_else(|| "arduino-cli".into());
    let mut cmd = tokio::process::Command::new(arduino_cli);
    cmd.args(["core", "uninstall", &platform]);
    stream_command(cmd, &app).await
}

#[tauri::command]
pub async fn update_core_index(app: tauri::AppHandle) -> Result<(), String> {
    let arduino_cli = binaries::path("arduino-cli").unwrap_or_else(|| "arduino-cli".into());
    let mut cmd = tokio::process::Command::new(arduino_cli);
    cmd.args(["core", "update-index"]);
    stream_command(cmd, &app).await
}

#[tauri::command]
pub async fn install_library(name: String, app: tauri::AppHandle) -> Result<(), String> {
    let arduino_cli = binaries::path("arduino-cli").unwrap_or_else(|| "arduino-cli".into());
    let mut cmd = tokio::process::Command::new(arduino_cli);
    cmd.args(["lib", "install", &name]);
    stream_command(cmd, &app).await
}

#[tauri::command]
pub async fn uninstall_library(name: String, app: tauri::AppHandle) -> Result<(), String> {
    let arduino_cli = binaries::path("arduino-cli").unwrap_or_else(|| "arduino-cli".into());
    let mut cmd = tokio::process::Command::new(arduino_cli);
    cmd.args(["lib", "uninstall", &name]);
    stream_command(cmd, &app).await
}

#[tauri::command]
pub async fn update_library_index(app: tauri::AppHandle) -> Result<(), String> {
    let arduino_cli = binaries::path("arduino-cli").unwrap_or_else(|| "arduino-cli".into());
    let mut cmd = tokio::process::Command::new(arduino_cli);
    cmd.args(["lib", "update-index"]);
    stream_command(cmd, &app).await
}
