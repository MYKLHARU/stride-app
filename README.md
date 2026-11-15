# Stride

A personal weight-loss tracker for Android: calorie and macro targets, food diary with barcode scanning (Open Food Facts), weight, water, steps and hunger tracking, recipes, workouts and progress photos.

## Get the app

Every push to `main` builds a new APK with GitHub Actions. Download it from the **Releases** section of this repository (the release called *latest*), on your Android phone, and open `Stride.apk` to install. You may need to allow installs from your browser the first time.

## How it's built

- `app/src/main/assets/index.html` is the whole app (HTML, CSS and JavaScript in one file).
- `MainActivity.java` shows it in a WebView and adds native extras: the Google barcode scanner, a camera/gallery photo picker, sharing backups, and the back button.
- Your data is stored on the phone (WebView local storage). Use **Profile > Back up or restore** to keep a copy.

To build locally, open the folder in Android Studio and choose **Build > Build APK(s)**.

## Notes

- Packaged products come from [Open Food Facts](https://uk.openfoodfacts.org) (Open Database Licence). Everyday foods come from McCance and Widdowson's Composition of Foods Integrated Dataset 2021: contains public sector information licensed under the Open Government Licence v3.0.
- `app/stride.keystore` signs the app so updates install over each other. It is only for this personal app. If you ever publish the app, make a new key and keep it out of the repository.
- Stride gives general guidance, not medical advice.
