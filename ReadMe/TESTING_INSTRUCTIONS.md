# 📋 TESTING INSTRUCTIONS - WITH DETAILED LOGGING

## 🔧 STEP 1: BUILD THE UPDATED APP

1. **File → Sync Project with Gradle Files** (wait for completion)
2. **Build → Rebuild Project**
3. **Run → Run 'app'** (install on your Android 11+ device)

## 📱 STEP 2: ENABLE ACCESSIBILITY SERVICE

1. Open the Family Guard app
2. **Enable DNS Lock** (set to 10 minutes for testing)
3. Go to **Settings → Accessibility → Family Guard**
4. Toggle the switch **ON**
5. **CRITICAL:** When prompted, grant **"Capture screen" permission** (this enables screenshot capture)
6. Confirm any additional permission prompts

## 📊 STEP 3: SET UP LOGGING

1. In Android Studio: **View → Tool Windows → Logcat**
2. Click the **"Clear logcat"** button (trash can icon)
3. In the search/filter box, type: `SmartContentDetector`
4. Set log level to **Debug** (or Verbose if available)

## 🧪 STEP 4: TEST WITH ACTUAL ADULT CONTENT

### Test Case 1: Explicit Site by Name (Should Block 98%)
1. Open **Chrome browser**
2. Go to: `pornhub.com`
3. Wait 2-3 seconds for scanning
4. **Expected Result:** 
   - Log shows: `EXPLICIT SITE DETECTED: pornhub`
   - Log shows: `AI result: blocked=true, confidence=0.98`
   - App goes to home screen
   - Toast shows: `⛔ BLOCKED: Adult site: pornhub\nConfidence: 98%`

### Test Case 2: Page with Adult Keywords (Should Block 50%+)
1. Open Chrome
2. Go to a site with text like: "This site contains xxx content, age verification required, 18+ only"
3. Wait 2-3 seconds
4. **Expected Result:**
   - Log shows text patterns matched: `xxx, age verification, 18+`
   - Log shows text suspicion score: 2.0+ (0.9 + 0.6 + 0.5)
   - Final score > 0.5 → blocked
   - Goes to home screen

### Test Case 3: Image-Based Ad (Depends on ML Kit)
1. Open Chrome
2. Go to a site with suggestive imagery (bikini, lingerie, etc.)
3. Wait 2-3 seconds
4. **Expected Result:**
   - Log shows: `ML Kit detected X labels`
   - Log shows individual labels with confidence
   - If NSFW labels found: `NSFW label matched: ...`
   - Combined with text score may trigger block

### Test Case 4: Safe Site (Should NOT Block)
1. Open Chrome
2. Go to: `sussex.ac.uk` or `wikipedia.org`
3. Wait 2-3 seconds
4. **Expected Result:**
   - Log shows: `Whitelisted: Educational/medical content`
   - OR shows low scores and `Content allowed`
   - Page loads normally (no blocking)

## 🔍 STEP 5: READING THE LOGS

Look for these key messages in Logcat (filter: SmartContentDetector):

```
D/SmartContentDetector: Extracted text length: XXX chars
D/SmartContentDetector: Text sample: [first 200 chars]
D/SmartContentDetector: Text patterns matched: [list if any]
D/SmartContentDetector: Text suspicion score: X.X
D/SmartContentDetector: Analyzing image: WIDTHxHEIGHT
D/SmartContentDetector: ML Kit detected X labels
D/SmartContentDetector: Label: [label] (confidence: XX%)
W/SmartContentDetector: NSFW label matched: [label] (confidence: XX%)
W/SmartContentDetector: Total NSFW labels found: X, max score: XX%
D/SmartContentDetector: Final score: X.XX (text: X.XX, image: X.XX)
D/SmartContentDetector: Threshold: 0.5, will block: true/false
```

## 📈 INTERPRETING RESULTS

### If You See:
- `Text patterns matched: xxx, nsfw` → Text detection working
- `Text suspicion score: 1.7` → Strong text indicators
- `ML Kit detected 15 labels` → Screenshot captured
- `Label: person (confidence: 85%)` → Generic labels (normal)
- `NSFW label matched: nudity (confidence: 78%)` → ML found adult content
- `Final score: 0.82` → Combined score over 0.5 threshold
- `will block: true` → Will block content
- `BLOCKING content: com.android.chrome` → Action taken

### If You See:
- `Extracted text length: 0 chars` → Accessibility not reading text
- `No text patterns matched` → No keywords found
- `ML Kit detected 0 labels` → Screenshot failed
- `No NSFW labels detected in image` → ML Kit not finding adult content
- `Final score: 0.12` → Low score, won't block
- `will block: false` → Content allowed (may be correct or false negative)

## 🛠️ TROUBLESHOOTING

### Problem: Always shows "Content allowed"
**Check:**
1. Is DNS lock active? (app UI should show active lock)
2. Did you grant screenshot permission in Accessibility settings?
3. Are you testing Chrome/Firefox/etc? (only scans risky apps)
4. Is cooldown active? Wait 2+ seconds between tests
5. Look for: `DNS lock not active` or `Skipping AI scan`

### Problem: Screenshot fails
**Check:**
1. Android 11+ required (you have minSdk=30)
2. Accessibility service has screenshot permission
3. Try toggling accessibility service OFF/ON
4. Look for: `Screenshot failed with code: X`

### Problem: Blocks too much (false positives)
**Check:**
1. Are you testing on educational sites? (should be whitelisted)
2. Look for: `Whitelisted: Educational/medical content`
3. If not whitelisted, check logs for false triggers
4. Consider raising threshold from 0.5 to 0.6 if needed

## 📊 EXPECTED DETECTION RATES

| Content Type | Detection Method | Expected Rate |
|--------------|------------------|---------------|
| Explicit site names (pornhub, etc.) | Keyword match | 98% |
| Pages with xxx/18+/nsfw text | Text patterns | 80-90% |
| Image-based ads (lingerie, etc.) | ML Kit + text | 40-60%* |
| Safe educational sites | Whitelist | 0% blocked |
| Work/social apps | Package whitelist | 0% blocked |

*ML Kit's generic labeler isn't optimized for adult detection, so image detection is weaker. Text detection is primary.

## ✅ SUCCESS CRITERIA

Your fix is working if:
1. **Explicit sites** like pornhub.com are blocked within 2 seconds
2. **Educational sites** like sussex.ac.uk are NOT blocked
3. **Logs show detailed analysis** (not just confidence=0.0)
4. **No compilation errors**
5. **Build succeeds**

---

**Ready to test!** Build, enable accessibility with screenshot permission, and watch the logs.

**Last Updated:** September 20, 2026, 11:15 AM UTC