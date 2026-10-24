# Privacy Policy

**Weatherd** is an Android live wallpaper that renders a procedural weather scene using [Open-Meteo](https://open-meteo.com), explicit ICON-family models served through Open-Meteo, or [MET Norway](https://api.met.no). The user chooses a primary weather provider and a global fallback. This policy explains what data the app uses and how.

## Data collected and stored

Settings are stored **locally on your device only** and are never transmitted to any server operated by this app.

| Data                                      | Purpose                                   | Where stored                       |
|-------------------------------------------|-------------------------------------------|------------------------------------|
| Primary and fallback weather providers   | Choose the preferred model and global fallback | Device storage (DataStore)    |
| Refresh interval                          | How often weather is re-fetched           | Device storage (DataStore)         |
| Location mode (device vs manual)          | Choose approximate GPS or a searched city | Device storage (DataStore)         |
| Manual place (label, latitude, longitude) | Remember a city you picked                | Device storage (DataStore)         |
| Photos you choose as backgrounds          | Draw them as the wallpaper's sky          | Device storage (app-private files) |

Approximate device location (when enabled and permitted) and manual coordinates are used only to request weather for that place. They are not stored on any server operated by this app.

A photo you pick for a part of the day is copied into the app's own private storage and kept there until you replace it. The copies never leave your device and are not transmitted anywhere; each one is deleted when you clear that part of the day or uninstall the app.

The optional **Widget diagnostics** capture in Advanced settings is off by default. When enabled, it stores a bounded local journal of widget lifecycle and update events, refresh-receiver and worker execution, and sanitized app-activity lifecycle events in private storage excluded from Android backup. Reports include event times, app/Android/device/launcher versions, diagnostic session and work IDs, and capture-health information, but omit coordinates, place names, weather payloads, arbitrary incoming intent data/extras, and exception messages. Capture settings and records survive app restarts and in-place updates; disabling capture keeps existing records, and **Clear diagnostic logs** deletes them without changing the capture setting. Nothing is uploaded automatically. **View and share report** lets you inspect the text and explicitly share it through Android's chooser; recipient privacy practices then apply.

## Third-party services

The app communicates with **[Open-Meteo](https://open-meteo.com)**:

- Forecast API — current weather for your chosen coordinates when Open-Meteo Best Match or an explicit DWD, ItaliaMeteo, or MeteoSwiss ICON model is used, including when one of those options is the configured fallback
- Geocoding API — city name search when you pick a place manually

The app also communicates with **[MET Norway](https://api.met.no)** when MET Norway is the effective primary or fallback provider:

- Locationforecast API — current weather for your chosen coordinates
- Sunrise API — sunrise, sunset, and solar state for your chosen coordinates

Regional models are used only within their supported geographic coverage. If the primary model does not cover the chosen coordinates or its request fails, Weatherd may make one request to the configured global fallback without changing either saved preference.

The experimental **Compare weather providers** action in Weather settings is opt-in and sends the same selected weather coordinates to Open-Meteo Best Match, ItaliaMeteo ICON-2I, DWD ICON EU, and MET Norway for a one-time comparison, regardless of the configured primary/fallback providers. It makes no requests until the tester taps **Compare now**. Comparison samples remain in memory and are not logged or uploaded to a Weatherd server. The tester may explicitly share a text report through Android's chooser; that report omits coordinates and place names but includes the selected observed sky, forecast fields, and UTC timestamps. Recipient privacy practices then apply.

Latitude and longitude are sent to the weather service actually used for a forecast request. City search queries are sent to Open-Meteo regardless of the selected forecast providers. MET Norway states that direct API access logs may contain the user's IP address and requested geocoordinates; those logs are operated by MET Norway and are subject to its own privacy policy and terms. Open-Meteo's own privacy policy applies to Open-Meteo traffic. Neither weather service requires an API key or user account in Weatherd.

No analytics, advertising, or tracking services are used.

## Permissions

| Permission               | Reason                                                                         |
|--------------------------|--------------------------------------------------------------------------------|
| `INTERNET`               | Fetch weather from the selected provider and geocoding from Open-Meteo          |
| `ACCESS_NETWORK_STATE`   | Check connectivity before network requests                                     |
| `ACCESS_COARSE_LOCATION` | Approximate location for weather (optional; you can use a manual city instead) |

The live wallpaper service uses the system wallpaper binder; it does not require additional runtime permissions beyond the above.

## Contact

For questions or concerns, open an issue at [https://github.com/Attacktive/weatherd/issues](https://github.com/Attacktive/weatherd/issues).
