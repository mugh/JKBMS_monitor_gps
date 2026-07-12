# JKBMS GPS Monitor For Ebike🔋🚀

An Android application designed to monitor **JK BMS (Jikong Battery Management System)** data in real-time via Bluetooth Low Energy (BLE), integrated with GPS data to calculate energy efficiency (Wh/km) and estimated remaining range, for use in ebike battery setup.

## Reason
The JK BMS app has an ugly and boring interface. I wanted an app that was more intuitive and capable of tracking power consumption based on distance, which is why I created this app.


## ✨ Key Features
- **Real-time Telemetry**: Monitor Voltage, Current, Power, SOC (State of Charge), and Capacity (Ah) directly from the BMS.
- **nRF Connect Style Bridge**: Smart connection that bypasses the need for encrypted handshake/login. Simply open the official JK BMS app once to "unlock" the device, and this app will passively read the data stream.
- **GPS Tracking**: Accurately tracks Speed (km/h), Trip Distance (km), and Trip Duration.
- **Efficiency Analytics**: Calculates average energy consumption in **Wh/km** and provides a dynamic range estimation based on remaining battery capacity.
- **Trip Logging & Export**: Automatically logs trip history with the ability to export data to **CSV** format for external analysis.
- **Modern Dashboard**: A responsive, dark-mode web-based UI (WebView) featuring JetBrains Mono for a clean, technical look.
- **Always-on Display**: Optional setting to keep the screen active while monitoring your ride.

## 🛠️ How It Works (BLE Bridge Protocol)
This app utilizes a passive monitoring technique for the JKBMS. 
- **Service UUID**: `0000ffe0-0000-1000-8000-00805f9b34fb`
- **Characteristic UUID**: `0000ffe1-0000-1000-8000-00805f9b34fb`

The app subscribes to notifications on the `ffe1` characteristic. Since JKBMS maintains a device-wide "unlocked" state once the official app performs the handshake, this application can process the *Summary Frames* without implementing the proprietary encryption layer.

## 🚀 Getting Started
1. **Open Official JK BMS App**: Ensure your phone is connected and the official app is actively displaying battery data.
2. **Launch JKBMS GPS Monitor**: Grant the required Bluetooth and Location permissions.
3. **Configure MAC Address**: Go to Settings (gear icon) and enter your BMS MAC Address.
4. **Start Trip**: Tap Start Trip to begin tracking distance and efficiency.
5. **Save Data**: Once finished, tap Finish Trip to save the record to your local history.

## 📦 Tech Stack
- **Kotlin**: Native BLE handling, GPS logic, and Android-WebView bridge.
- **WebView (HTML5/CSS3/JS)**: Dynamic and responsive dashboard UI.
- **Material Components**: Settings dialogs and system UI.
- **Jetpack Commons**: SharedPreferences for persistent configuration.

## 📝 Important Note
> This application operates as a "Bridge." If data does not appear, ensure the official JK BMS app is running in the background or was recently opened to "unlock" the BLE access on the BMS unit.

---
Developed by **Mughni** ⚡
