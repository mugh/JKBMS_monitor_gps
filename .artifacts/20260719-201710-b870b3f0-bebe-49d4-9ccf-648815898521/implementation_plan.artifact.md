# Fix Startup Crash due to Theme Incompatibility

The app was crashing immediately after the splash screen because it used Material Components (like `MaterialCardView` and `MaterialButton`) but was using an `AppCompat` theme. Material Components require a `Theme.MaterialComponents` theme (or a descendant) to function.

## Proposed Changes

### Theme Configuration

#### [NEW] [themes.xml](file:///C:/Users/MUGHN/Desktop/JKBMS_monitor_gps/app/src/main/res/values/themes.xml)

- Created a new theme `Theme.SBMSBridge` inheriting from `Theme.MaterialComponents.DayNight.NoActionBar`.
- Configured primary and secondary colors using existing project colors.

#### [AndroidManifest.xml](file:///C:/Users/MUGHN/Desktop/JKBMS_monitor_gps/app/src/main/AndroidManifest.xml)

- Updated the application theme to use the newly created `Theme.SBMSBridge`.

## Verification Plan

### Manual Verification
- Deploy the app to the device.
- Observe that the app starts successfully without crashing.
- Verify the UI is rendered correctly and the clock is updating.
- Check `logcat` to ensure no `Fatal Exception` occurs during startup.
