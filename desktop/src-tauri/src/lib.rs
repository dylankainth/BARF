pub mod arduino_manager;
pub mod binaries;
pub mod compile;
pub mod pairing_server;
pub mod phone_bridge;

use std::sync::Mutex;
use tauri::State;

/// Application-wide state shared across Tauri commands.
pub struct AppState {
    pub phone_ip: Mutex<String>,
}

#[tauri::command]
async fn get_status(state: State<'_, AppState>) -> Result<String, String> {
    let ip = state.phone_ip.lock().map_err(|e| e.to_string())?;
    if ip.is_empty() {
        Ok("Not paired".to_string())
    } else {
        Ok(format!("Connected to phone at {}", ip))
    }
}

#[tauri::command]
async fn start_pairing(
    pairing: State<'_, pairing_server::PairingState>,
) -> Result<String, String> {
    // Generate 12-char hex pair key
    let pair_key: String = (0..12)
        .map(|_| format!("{:x}", rand::random::<u8>() % 16))
        .collect();

    pairing.set_expected_key(pair_key.clone()).await;

    // Detect LAN IP
    let desktop_ip = local_ip_address::local_ip()
        .map(|ip| ip.to_string())
        .unwrap_or_else(|_| "127.0.0.1".to_string());

    let result = serde_json::json!({
        "desktop_ip": desktop_ip,
        "pair_key": pair_key,
        "port": 9876
    });

    Ok(result.to_string())
}

#[tauri::command]
async fn get_paired_phone_ip(
    pairing: State<'_, pairing_server::PairingState>,
) -> Result<String, String> {
    match pairing.get_paired_ip().await {
        Some(ip) => Ok(ip),
        None => Ok("none".to_string()),
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let pairing_state = pairing_server::PairingState::new();
    let pairing_state_clone = pairing_state.clone();

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .manage(AppState {
            phone_ip: Mutex::new(String::new()),
        })
        .manage(pairing_state)
        .invoke_handler(tauri::generate_handler![
            get_status,
            compile::compile_wasm,
            compile::compile_esp32,
            compile::flash_esp32,
            compile::list_serial_ports,
            phone_bridge::deploy_wasm,
            phone_bridge::get_phone_status,
            start_pairing,
            get_paired_phone_ip,
            arduino_manager::get_additional_urls,
            arduino_manager::add_additional_url,
            arduino_manager::remove_additional_url,
            arduino_manager::list_installed_platforms,
            arduino_manager::search_platforms,
            arduino_manager::install_platform,
            arduino_manager::uninstall_platform,
            arduino_manager::update_core_index,
            arduino_manager::list_installed_libraries,
            arduino_manager::search_libraries,
            arduino_manager::install_library,
            arduino_manager::uninstall_library,
            arduino_manager::update_library_index,
        ])
        .setup(move |_app| {
            tauri::async_runtime::spawn(async move {
                if let Err(e) =
                    pairing_server::start_pairing_server(9876, pairing_state_clone).await
                {
                    eprintln!("[pairing] Failed to start server: {}", e);
                }
            });
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
