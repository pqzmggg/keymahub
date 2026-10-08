# The accessibility service is referenced from the manifest only.
-keep class app.keymahub.KeymaAccessibilityService { *; }

# Hidden in the SDK but called by the Bluetooth stack (BleHid logs connection interval changes).
-keepclassmembers class * extends android.bluetooth.BluetoothGattServerCallback {
    public void onConnectionUpdated(android.bluetooth.BluetoothDevice, int, int, int, int);
}
