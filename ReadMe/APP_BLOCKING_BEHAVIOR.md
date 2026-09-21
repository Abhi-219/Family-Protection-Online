# App Blocking Behavior

## When enforcement is active

`AntiUninstallAccessibilityService` receives accessibility events when:

1. The Accessibility Service is enabled in Android Settings.
2. `DnsPreferences.isActive(context)` returns `true`.
3. The lock expiry time has not passed.

If the lock is inactive or expired, the listener logs the event and does not block content.

The service listens for:

- Window changes
- Window content changes
- View scrolling

This allows it to react when a user changes Chrome tabs or when visible page content changes.

## Apps blocked immediately

These packages are blocked before AI analysis runs:

| App | Package | Behavior |
|---|---|---|
| Bumble | `com.bumble.app` | Returns to Home |
| Tinder | `com.tinder` | Returns to Home |
| Plenty of Fish | `com.pof.android` | Returns to Home |
| Hily | `com.hily.app` | Returns to Home |

The package check matches the exact package and package names beginning with the package plus a dot.

Example:

```text
com.tinder
com.tinder.something
```

Both are treated as blocked.

## AI-scanned apps

The service captures a screenshot and sends it to `SmartContentDetector` for apps in these groups.

### Browsers

Chrome, Firefox, Opera, Edge, Brave, DuckDuckGo, Samsung Internet, Vivaldi, Kiwi, Tor Browser, Ecosia, Yandex, UC Browser, Android Browser, Qwant, Ghostery, and other configured browser packages.

### Social, messaging, media, and dating apps

Instagram, Facebook, TikTok, X/Twitter, Snapchat, Telegram, Discord, Reddit, Pinterest, Tumblr, Messenger, WhatsApp, Google Messages, Viber, LINE, WeChat, VK, Quora, Signal, KakaoTalk, Clubhouse, Slack, Twitch, Likee, Bigo, Kwai, Hinge, Bumble, Tinder, POF, Hily, OkCupid, Grindr, Match, and other configured packages.

AI scanning does not automatically block the app itself. It blocks only when the visible content is classified as adult content.

## How AI blocking works

For an AI-scanned app, the service:

1. Captures the current screen.
2. Waits for the screenshot callback to complete.
3. Confirms the same app is still in the foreground.
4. Reads accessibility text from the current window.
5. Runs OCR on visible screenshot text.
6. Runs the on-device NSFW TensorFlow Lite model.
7. Runs ML Kit image labeling.
8. Combines the results.

The detector can block when it finds:

- Known explicit site names such as `xvideos` or `pornhub`.
- Adult-content keywords in accessibility text or OCR text.
- A sufficiently high NSFW model score.
- Matching ML Kit labels.

No screenshot or page content is uploaded by the NSFW model; the model runs on-device.

## Blocking action

When content is blocked, the service:

1. Logs the detection result.
2. Calls `performGlobalAction(GLOBAL_ACTION_HOME)`.
3. Shows a blocked-content message.
4. Checks whether the monitored app is still foreground.
5. Retries the Home action once after 250 ms if necessary.

Relevant log sequence:

```text
AI result: blocked=true
BLOCKING content: com.android.chrome - Adult site: xvideos
Home action accepted=true
```

## Apps that are not blocked

The following productivity apps are exempt from content scanning:

- Gmail
- Outlook
- Zoom
- Microsoft Teams
- Google Docs
- Google Calendar

The Family app itself is also exempt from its own content scan.

## Installation and uninstall behavior

The app does not automatically block installation of every adult app.

Device Owner policy is package-specific:

- While the lock is active, uninstalling the Family app is blocked.
- Other installed apps remain uninstallable.
- When the lock is inactive or expired, the Family app uninstall restriction is removed.

This requires the Family app to be provisioned as the Device Owner. A normal app cannot silently enable Accessibility, Device Owner, or protected permissions.

## Important limitations

- Unknown apps are not automatically AI-scanned unless their package is added to the configured browser or social-app lists.
- AI detects visible content; it does not understand every app's purpose.
- Image-only or heavily obfuscated content may be missed.
- Screenshot capture can fail on some device manufacturers or restricted screens.
- The accessibility service must remain enabled for enforcement to work.
- The lock must remain active and unexpired.
