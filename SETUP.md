# WebHawk — Firebase Setup & Project Guide

## Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- A Google account for Firebase
- Inter font files (see Font Setup below)

---

## 1. Firebase Project Setup

### 1.1 Create the Firebase Project
1. Go to [https://console.firebase.google.com](https://console.firebase.google.com)
2. Click **Add project** → Name it `WebHawk`
3. Disable Google Analytics (optional, or keep it on)
4. Click **Create project**

### 1.2 Add Android App to Firebase
1. In your Firebase project dashboard, click the **Android icon** (Add app)
2. Enter package name: `com.webhawk.detector`
3. Enter app nickname: `WebHawk`
4. Click **Register app**
5. **Download `google-services.json`**
6. Place it at: `app/google-services.json`
   ```
   MiniProject/
   └── app/
       └── google-services.json   ← here
   ```
7. Click **Next** through the remaining steps (SDK already configured in `build.gradle.kts`)

---

## 2. Firebase Authentication Setup

1. In Firebase Console → **Build** → **Authentication**
2. Click **Get started**
3. Under **Sign-in method** tab → click **Email/Password**
4. Toggle **Enable** → Save

---

## 3. Cloud Firestore Setup

### 3.1 Create the Database
1. Firebase Console → **Build** → **Firestore Database**
2. Click **Create database**
3. Choose **Start in production mode** (rules applied next)
4. Select a region close to your users (e.g., `asia-south1` for India)
5. Click **Enable**

### 3.2 Deploy Security Rules
**Option A — Firebase CLI (recommended):**
```bash
# Install Firebase CLI
npm install -g firebase-tools

# Login
firebase login

# Initialize in project root (select Firestore)
firebase init firestore

# Deploy rules
firebase deploy --only firestore:rules,firestore:indexes
```

**Option B — Manual paste:**
1. Firebase Console → Firestore → **Rules** tab
2. Copy contents of `firestore.rules` → paste → **Publish**
3. Firebase Console → Firestore → **Indexes** tab → create composite indexes from `firestore.indexes.json`

### 3.3 Required Composite Indexes
Create these in the **Indexes** tab:

| Collection | Fields | 
|---|---|
| `flagged_urls` | `normalizedUrl ASC`, `flagCount DESC` |
| `flagged_urls` | `validatedFlag ASC`, `weightedScore DESC` |
| `user_flags` | `userId ASC`, `flaggedAt DESC` |

---

## 4. Font Setup (Inter)

Download the Inter font from [https://rsms.me/inter/](https://rsms.me/inter/) and place these three files:

```
app/src/main/res/font/
├── inter_regular.ttf      (Inter-Regular.ttf)
├── inter_semibold.ttf     (Inter-SemiBold.ttf)
└── inter_bold.ttf         (Inter-Bold.ttf)
```

---

## 5. Accessibility Service Setup (User-facing)

After installing the app, the user must:
1. Open **Settings** → **Accessibility** (or **Additional Settings** → **Accessibility** on some devices)
2. Find **WebHawk URL Monitor** under Downloaded Apps
3. Toggle it **ON** and confirm permissions

The app will show a warning banner when the service is disabled.

---

## 6. Firestore Data Schema

### `users/{uid}`
```json
{
  "uid": "string",
  "email": "string",
  "displayName": "string",
  "trustScore": 1.0,
  "flagCount": 0,
  "validatedFlagCount": 0,
  "isAdmin": false,
  "createdAt": "timestamp"
}
```

### `flagged_urls/{docId}`
```json
{
  "url": "string",
  "normalizedUrl": "string",
  "flagCount": 1,
  "validatedFlag": false,
  "weightedScore": 1.0,
  "createdAt": "timestamp",
  "updatedAt": "timestamp"
}
```

### `user_flags/{flagId}`
```json
{
  "userId": "string",
  "urlId": "string",
  "url": "string",
  "riskScore": 4.5,
  "chain": ["url1", "url2"],
  "flaggedAt": "timestamp"
}
```

---

## 7. Making a User Admin

Run this one-time in Firestore Console or via Firebase Admin SDK:

```javascript
// Firebase Console → Firestore → users → {uid} → Edit document
// Set field: isAdmin = true (boolean)
```

Or via Firebase Admin SDK (Node.js):
```javascript
const admin = require('firebase-admin');
admin.initializeApp({ credential: admin.credential.applicationDefault() });

await admin.firestore()
  .collection('users')
  .doc('USER_UID_HERE')
  .update({ isAdmin: true });
```

---

## 8. Risk Score Formula Reference

```
local_score  = 2.0 × redirect_count
             + 1.5 × unique_domains
             + 1.2 × has_shortener   (1 = yes, 0 = no)
             + 1.0 × suspicious_tld  (1 = yes, 0 = no)
             − 0.5 × avg_interval_seconds

final_risk   = local_score × 0.7 + global_reputation × 0.3
```

### Risk Level Thresholds
| Score | Level |
|---|---|
| < 2.0 | SAFE |
| 2.0 – 4.9 | LOW |
| 5.0 – 7.9 | MEDIUM |
| 8.0 – 11.9 | HIGH |
| ≥ 12.0 | CRITICAL |

---

## 9. Trust Score System

- New users start with `trustScore = 1.0`
- When a user's flag is **admin-validated**, their weight grows automatically
- The admin can manually adjust `trustScore` in Firestore
- Flag weight = `user.trustScore` added to `flagged_urls.weightedScore`
- `global_reputation = weightedScore / 5.0` (capped at 10.0)

---

## 10. Supported Browsers (Accessibility Monitoring)

The system reads the address bar from these browser packages:
- Google Chrome (`com.android.chrome`)
- Chrome Beta/Dev/Canary
- Microsoft Edge (`com.microsoft.emmx`)
- Brave Browser (`com.brave.browser`)
- Opera Browser (`com.opera.browser`)
- Opera Mini (`com.opera.mini.native`)

**Note:** Firefox uses a different accessibility tree and is not currently supported.

---

## 11. Build & Run

```bash
# Sync Gradle
./gradlew build

# Install debug APK
./gradlew installDebug
```

Make sure `google-services.json` and the Inter font `.ttf` files are in place before building.

---

## 12. Project Structure

```
app/src/main/java/com/webhawk/detector/
├── WebHawkApp.kt                          # Application class
├── data/
│   ├── model/Models.kt                    # All data models (Parcelable)
│   └── repository/
│       ├── AuthRepository.kt              # Firebase Auth
│       └── FlagRepository.kt             # Firestore flag DB
├── engine/
│   ├── FeatureExtractor.kt               # Redirect chain analysis
│   └── RiskEngine.kt                     # Scoring formula
├── service/
│   ├── WebHawkAccessibilityService.kt    # Browser monitor
│   └── UrlChangeLogger.kt               # Redirect chain recorder
└── ui/
    ├── splash/SplashActivity.kt
    ├── auth/
    │   ├── AuthActivity.kt
    │   └── AuthViewModel.kt
    ├── main/
    │   ├── MainActivity.kt
    │   └── MainViewModel.kt
    └── result/
        ├── ResultBottomSheet.kt
        └── RedirectChainAdapter.kt
```
