# Draft — Replace draw-over-other-apps with notification for background Relay

Server closing — next session implement this, don't do now.

## Goal
Remove `SYSTEM_ALERT_WINDOW` (draw over) and use a high-priority notification as fallback for background Relay. Automatic `startActivity` works when foreground; when blocked in background (Android 10+ BAL), show `Relay: tap to open spotify.com` notification with PendingIntent. Easily reversible (single file + perm).

## Why
- `ACTION_MANAGE_OVERLAY_PERMISSION` deep link ignored on API 30+ (Android 11) — always goes to list (verified via web search, AOSP docs). Poor UX.
- Notification path is Play-friendly, no special Settings deep link, works on all APIs.

## Steps (next session)

1. **Remove overlay (easily removable):**
   - Delete `app/src/main/java/com/uncry/OverlayHelper.kt`
   - Remove `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />` from `app/src/main/AndroidManifest.xml`
   - In `MainActivity.kt`: delete `overlayDialog` field, `maybePromptOverlayPermission()` method, its calls in `onCreate`/`onResume`/`onDestroy`, overlay status line in `refreshMonitorUi()`, and `OverlayHelper` import

2. **Re-add notification permission (we removed on poss):**
   - Manifest: add `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`
   - Keep existing foreground-service notification channel (already exempt), add new high-importance channel for Relay if needed

3. **MainActivity: request notification permission (Android 13+):**
   - Add `ActivityResultLauncher` for `Manifest.permission.POST_NOTIFICATIONS` (like old `notifPermissionLauncher` we deleted)
   - On launch, if `Build.VERSION.SDK_INT >= 33` and `checkSelfPermission != GRANTED`, launch it; gate is optional — Relay notification will just not show if denied, direct launch still attempted

4. **DeviceRegistrar: background Relay logic**
   - Keep `openRelayUrl(app, url)` trying direct `startActivity(ACTION_VIEW)` first (works foreground or if system allows)
   - On catch `SecurityException` / generic or pre-check `isBackground`, post notification:
     ```kotlin
     val pi = PendingIntent.getActivity(app, deviceId.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(FLAG_ACTIVITY_NEW_TASK), FLAG_IMMUTABLE)
     NotificationCompat.Builder(app, "uncry_relay") // IMPORTANCE_HIGH channel
       .setSmallIcon(android.R.drawable.ic_dialog_info)
       .setContentTitle("Relay")
       .setContentText("Tap to open spotify.com")
       .setContentIntent(pi).setAutoCancel(true).setPriority(PRIORITY_HIGH)
     ```
   - Keep 5s poll in `AppMonitorService` (already calls `pollCommandsAsync`), no change

5. **Test:**
   - Foreground: click Relay on dashboard → device opens directly (no notification)
   - Background (app swiped, service foreground): click Relay → notification appears within 5s → tap → opens spotify.com

## Keep
- Teller per-device Relay `POST /api/devices/[id]/relay` -> Relayer queue -> device polls 5s (already implemented, no change)
- Relayer `commands` Map + `POST /relay/relay` + `GET /relay/poll/:id` (no change)
- Dashboard per-device Relay buttons (already POST, no change)

## Files to touch next session
- `app/src/main/AndroidManifest.xml` (perm swap)
- `app/src/main/java/com/uncry/MainActivity.kt` (remove overlay, add notif launcher)
- `app/src/main/java/com/uncry/DeviceRegistrar.kt` (add notification fallback in `openRelayUrl`)
- Delete `OverlayHelper.kt`
