/**
 * AudioPlayer.java
 * 
 * Plays MP3 audio files from the assets folder.
 */
package com.tencent.yolo11ncnn;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class AudioPlayer {
    private static final String TAG = "AudioPlayer";
    
    private Context context;
    private AssetManager assetManager;
    private MediaPlayer currentPlayer;
    private Map<String, String> audioFiles = new HashMap<>();
    
    public AudioPlayer(Context context) {
        this.context = context;
        this.assetManager = context.getAssets();
        loadAudioFiles();
    }
    
    /**
     * Load available audio files from assets/audio folder.
     */
    private void loadAudioFiles() {
        try {
            String[] files = assetManager.list("audio");
            if (files != null) {
                for (String file : files) {
                    if (file.endsWith(".mp3") || file.endsWith(".wav")) {
                        String name = file.substring(0, file.lastIndexOf('.'));
                        audioFiles.put(name, "audio/" + file);
                        Log.d(TAG, "Audio file registered: " + name);
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to load audio files: " + e.getMessage());
        }
    }
    
    /**
     * Play an audio file by name (without extension).
     * 
     * @param audioName Name of audio file (e.g., "beep" for "beep.mp3")
     */
    public synchronized void play(String audioName) {
        if (!audioFiles.containsKey(audioName)) {
            Log.w(TAG, "Audio file not found: " + audioName);
            return;
        }
        
        String assetPath = audioFiles.get(audioName);
        
        try {
            // Stop and release previous player if any
            if (currentPlayer != null) {
                currentPlayer.stop();
                currentPlayer.release();
            }
            
            // Create new media player
            currentPlayer = new MediaPlayer();
            
            // Open asset file
            try (java.io.InputStream input = assetManager.open(assetPath)) {
                java.io.File tempFile = java.io.File.createTempFile("audio", ".mp3", context.getCacheDir());
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = input.read(buffer)) > 0) {
                        fos.write(buffer, 0, length);
                    }
                }
                
                currentPlayer.setDataSource(tempFile.getAbsolutePath());
                currentPlayer.prepare();
                currentPlayer.start();
                
                currentPlayer.setOnCompletionListener(mp -> {
                    mp.release();
                    tempFile.delete();
                });
                
                Log.i(TAG, "Playing audio: " + audioName);
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to play audio: " + e.getMessage(), e);
            if (currentPlayer != null) {
                try {
                    currentPlayer.release();
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to release player: " + ex.getMessage());
                }
            }
        }
    }
    
    /**
     * Stop current playback.
     */
    public synchronized void stop() {
        if (currentPlayer != null) {
            try {
                if (currentPlayer.isPlaying()) {
                    currentPlayer.stop();
                }
                currentPlayer.release();
                currentPlayer = null;
                Log.i(TAG, "Audio playback stopped");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audio: " + e.getMessage());
            }
        }
    }
    
    /**
     * Get list of available audio files.
     */
    public String[] getAvailableAudio() {
        return audioFiles.keySet().toArray(new String[0]);
    }
    
    /**
     * Check if audio file exists.
     */
    public boolean hasAudio(String audioName) {
        return audioFiles.containsKey(audioName);
    }
    
    /**
     * Cleanup resources.
     */
    public void release() {
        stop();
    }
}
