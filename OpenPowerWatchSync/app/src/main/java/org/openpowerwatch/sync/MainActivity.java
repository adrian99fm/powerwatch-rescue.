package org.openpowerwatch.sync;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Clean-room BLE utility for legacy PowerWatch time sync.
 * Contains no original app assets and no private device identifiers.
 */
public class MainActivity extends Activity {
    private static final UUID SERVICE_UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e1011");
    private static final UUID NOTIFY_UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e0012");
    private static final UUID PWX_WRITE_UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e0013");
    private static final UUID CLASSIC_WRITE_UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e0011");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final byte[] HEADER = new byte[] {
            0x14, 0x00, 0x01, 0x00, 0x00, 0x64, 0x00, 0x00,
            0x00, (byte) 0xCA, (byte) 0xC6, (byte) 0x88, (byte) 0x99, 0x00, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xA9, 0x4C, (byte) 0xA7, 0x21
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
    private final Queue<byte[]> pendingWrites = new ArrayDeque<>();
    private final List<byte[]> pendingChunks = new ArrayList<>();

    private TextView status;
    private TextView logView;
    private Button scanButton;
    private Button syncButton;
    private EditText macInput;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothDevice selectedDevice;
    private boolean scanning = false;
    private boolean writeInProgress = false;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            if (d == null) return;
            String name = safeName(d);
            String addr = safeAddress(d);
            String key = addr + "  " + name;
            if (!found.containsKey(key)) {
                found.put(key, d);
                log("Found: " + key);
            }
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int statusCode, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected. Discovering services...");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected. status=" + statusCode);
                setStatus("Disconnected");
                writeInProgress = false;
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                fail("Service discovery failed: " + statusCode);
                return;
            }
            BluetoothGattService svc = g.getService(SERVICE_UUID);
            if (svc == null) {
                fail("PowerWatch service not found");
                return;
            }
            BluetoothGattCharacteristic notify = svc.getCharacteristic(NOTIFY_UUID);
            writeChar = svc.getCharacteristic(PWX_WRITE_UUID);
            if (writeChar == null) writeChar = svc.getCharacteristic(CLASSIC_WRITE_UUID);
            if (writeChar == null) {
                fail("Write characteristic not found");
                return;
            }
            log("Using write characteristic: " + writeChar.getUuid());
            if (notify != null) {
                boolean ok = g.setCharacteristicNotification(notify, true);
                log("Enable notify local: " + ok);
                BluetoothGattDescriptor desc = notify.getDescriptor(CCCD_UUID);
                if (desc != null) {
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(desc);
                    return;
                }
            }
            beginSyncWrites();
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int statusCode) {
            log("Notify descriptor write status=" + statusCode);
            beginSyncWrites();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] v = characteristic.getValue();
            log("Notify " + characteristic.getUuid() + ": " + hex(v) + "  ascii=" + printable(v));
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int statusCode) {
            log("Write status=" + statusCode);
            writeInProgress = false;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                fail("Write failed: " + statusCode);
                return;
            }
            drainNextChunkOrCommand();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        buildUi();
        requestNeededPermissions();
        setStatus("Ready");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Open PowerWatch Sync");
        title.setTextSize(24);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Clean-room BLE time sync utility. No cloud, no vendor assets.");
        root.addView(sub);

        macInput = new EditText(this);
        macInput.setHint("Optional MAC address (AA:BB:CC:DD:EE:FF)");
        macInput.setSingleLine(true);
        macInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        macInput.setText(getPrefs().getString("last_mac", ""));
        root.addView(macInput);

        scanButton = new Button(this);
        scanButton.setText("Scan / choose watch");
        scanButton.setOnClickListener(v -> startScanAndChoose());
        root.addView(scanButton);

        syncButton = new Button(this);
        syncButton.setText("Sync time now");
        syncButton.setOnClickListener(v -> syncNow());
        root.addView(syncButton);

        status = new TextView(this);
        status.setTextSize(18);
        root.addView(status);

        logView = new TextView(this);
        logView.setTextSize(13);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private SharedPreferences getPrefs() { return getSharedPreferences("settings", MODE_PRIVATE); }

    private void setStatus(String s) { handler.post(() -> status.setText("Status: " + s)); }

    private void log(String s) {
        handler.post(() -> {
            String line = String.format(Locale.US, "%tT  %s\n", new Date(), s);
            logView.append(line);
        });
    }

    private void fail(String msg) {
        setStatus("ERROR");
        log("ERROR: " + msg);
    }

    @SuppressLint("MissingPermission")
    private void startScanAndChoose() {
        if (!hasPermissions()) { requestNeededPermissions(); return; }
        if (adapter == null || !adapter.isEnabled()) { fail("Bluetooth is off"); return; }
        found.clear();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { fail("BLE scanner not available"); return; }
        setStatus("Scanning...");
        log("Scanning for 8 seconds...");
        scanning = true;
        scanner.startScan(scanCallback);
        handler.postDelayed(() -> {
            if (!scanning) return;
            scanning = false;
            scanner.stopScan(scanCallback);
            showDeviceChooser();
        }, 8000);
    }

    @SuppressLint("MissingPermission")
    private void showDeviceChooser() {
        if (found.isEmpty()) { fail("No BLE devices found. Try entering MAC manually."); return; }
        List<String> keys = new ArrayList<>(found.keySet());
        ArrayAdapter<String> arr = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, keys);
        new AlertDialog.Builder(this)
                .setTitle("Choose watch")
                .setAdapter(arr, (d, which) -> {
                    selectedDevice = found.get(keys.get(which));
                    macInput.setText(safeAddress(selectedDevice));
                    getPrefs().edit().putString("last_mac", safeAddress(selectedDevice)).apply();
                    log("Selected: " + keys.get(which));
                    setStatus("Selected");
                })
                .show();
    }

    @SuppressLint("MissingPermission")
    private void syncNow() {
        if (!hasPermissions()) { requestNeededPermissions(); return; }
        if (adapter == null || !adapter.isEnabled()) { fail("Bluetooth is off"); return; }
        String mac = macInput.getText().toString().trim();
        try {
            if (selectedDevice == null && mac.length() > 0) selectedDevice = adapter.getRemoteDevice(mac);
        } catch (IllegalArgumentException e) {
            fail("Bad MAC address");
            return;
        }
        if (selectedDevice == null) { fail("Choose device or enter MAC first"); return; }
        getPrefs().edit().putString("last_mac", safeAddress(selectedDevice)).apply();
        if (gatt != null) { gatt.close(); gatt = null; }
        pendingWrites.clear();
        pendingChunks.clear();
        writeInProgress = false;
        setStatus("Connecting...");
        log("Connecting to " + safeAddress(selectedDevice) + " ...");
        gatt = selectedDevice.connectGatt(this, false, gattCallback);
    }

    private void beginSyncWrites() {
        pendingWrites.clear();
        pendingWrites.add(frame(timePayload()));
        pendingWrites.add(frame(new byte[] {(byte)0x84, 0x21}));
        pendingWrites.add(frame(new byte[] {(byte)0x81, 0x0F, 0x05, 0x00, (byte)0x82}));
        log("Queued time + save + SOFF");
        setStatus("Syncing...");
        drainNextChunkOrCommand();
    }

    @SuppressLint("MissingPermission")
    private void drainNextChunkOrCommand() {
        if (writeInProgress || gatt == null || writeChar == null) return;
        if (pendingChunks.isEmpty()) {
            byte[] next = pendingWrites.poll();
            if (next == null) {
                setStatus("Sync OK");
                log("SYNC OK. Check watch display.");
                handler.postDelayed(() -> {
                    if (gatt != null) { gatt.disconnect(); }
                }, 1000);
                return;
            }
            pendingChunks.addAll(chunk20(next));
        }
        byte[] chunk = pendingChunks.remove(0);
        writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        writeChar.setValue(chunk);
        log("Write chunk: " + hex(chunk));
        writeInProgress = true;
        boolean ok = gatt.writeCharacteristic(writeChar);
        if (!ok) {
            writeInProgress = false;
            fail("writeCharacteristic returned false");
        }
    }

    private static byte[] timePayload() {
        long nowMs = System.currentTimeMillis();
        long utc = nowMs / 1000L;
        int offsetMin = TimeZone.getDefault().getOffset(nowMs) / 60000;
        return new byte[] {
                (byte)0x84, 0x01, 0x07,
                (byte)((utc >> 24) & 0xff), (byte)((utc >> 16) & 0xff), (byte)((utc >> 8) & 0xff), (byte)(utc & 0xff),
                (byte)((offsetMin >> 8) & 0xff), (byte)(offsetMin & 0xff)
        };
    }

    private static byte[] frame(byte[] rawPayload) {
        String b64 = Base64.encodeToString(rawPayload, Base64.NO_WRAP);
        byte[] text = ("!" + b64 + "\n").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(HEADER, 0, HEADER.length);
        out.write((byte) text.length);
        out.write(text, 0, text.length);
        return out.toByteArray();
    }

    private static List<byte[]> chunk20(byte[] msg) {
        List<byte[]> chunks = new ArrayList<>();
        for (int i = 0; i < msg.length; i += 20) {
            chunks.add(Arrays.copyOfRange(msg, i, Math.min(i + 20, msg.length)));
        }
        return chunks;
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[] { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT }, 42);
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, 42);
        }
    }

    @SuppressLint("MissingPermission")
    private static String safeName(BluetoothDevice d) {
        try { String n = d.getName(); return n == null ? "(no name)" : n; } catch (SecurityException e) { return "(name hidden)"; }
    }

    @SuppressLint("MissingPermission")
    private static String safeAddress(BluetoothDevice d) {
        try { return d.getAddress(); } catch (SecurityException e) { return ""; }
    }

    private static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02X ", x & 0xff));
        return sb.toString().trim();
    }

    private static String printable(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            int c = x & 0xff;
            sb.append(c >= 32 && c <= 126 ? (char)c : '.');
        }
        return sb.toString();
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
}
