# Privacy Policy

**Weatherd** is an Android live wallpaper that renders a procedural weather scene using [Open-Meteo](https://open-meteo.com) or [MET Norway](https://api.met.no), selected by the user. This policy explains what data the app uses and how.

## Data collected and stored

Settings are stored **locally on your device only** and are never transmitted to any server operated by this app.

| Data                                      | Purpose                                   | Where stored                       |
|-------------------------------------------|-------------------------------------------|------------------------------------|
| Weather provider                         | Choose the service used for weather       | Device storage (DataStore)         |
| Refresh interval                          | How often weather is re-fetched           | Device storage (DataStore)         |
| Location mode (device vs manual)          | Choose approximate GPS or a searched city | Device storage (DataStore)         |
| Manual place (label, latitude, longitude) | Remember a city you picked                | Device storage (DataStore)         |
| Photos you choose as backgrounds          | Draw them as the wallpaper's sky          | Device storage (app-private files) |

Approximate device location (when enabled and permitted) and manual coordinates are used only to request weather for that place. They are not stored on any server operated by this app.

A photo you pick for a part of the day is copied into the app's own private storage and kept there until you replace it. The copies never leave your device and are not transmitted anywhere; each one is deleted when you clear that part of the day or uninstall the app.

## Third-party services

The app communicates with **[Open-Meteo](https://open-meteo.com)**:

- Forecast API — current weather for your chosen coordinates when Open-Meteo is selected
- Geocoding API — city name search when you pick a place manually

The app also communicates with **[MET Norway](https://api.met.no)** when MET Norway is selected:

- Locationforecast API — current weather for your chosen coordinates
- Sunrise API — sunrise, sunset, and solar state for your chosen coordinates

Latitude and longitude are sent to the selected weather provider for forecast requests. City search queries are sent to Open-Meteo regardless of the selected forecast provider. MET Norway states that direct API access logs may contain the user's IP address and requested geocoordinates; those logs are operated by MET Norway and are subject to its own privacy policy and terms. Open-Meteo's own privacy policy applies to Open-Meteo traffic. Neither weather provider requires an API key or user account in Weatherd.

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
