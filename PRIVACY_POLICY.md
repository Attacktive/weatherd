# Privacy Policy

**Weatherd** is an Android live wallpaper that renders a procedural weather scene from [Open-Meteo](https://open-meteo.com). This policy explains what data the app uses and how.

## Data collected and stored

Settings are stored **locally on your device only** and are never transmitted to any server operated by this app.

| Data                                      | Purpose                                   | Where stored               |
|-------------------------------------------|-------------------------------------------|----------------------------|
| Refresh interval                          | How often weather is re-fetched           | Device storage (DataStore) |
| Location mode (device vs manual)          | Choose approximate GPS or a searched city | Device storage (DataStore) |
| Manual place (label, latitude, longitude) | Remember a city you picked                | Device storage (DataStore) |

Approximate device location (when enabled and permitted) and manual coordinates are used only to request weather for that place. They are not stored on any server operated by this app.

## Third-party services

The app communicates with **[Open-Meteo](https://open-meteo.com)**:

- Forecast API — current weather for your chosen coordinates
- Geocoding API — city name search when you pick a place manually

Latitude and longitude (and city search queries) are sent to Open-Meteo for those requests. Open-Meteo's own privacy policy applies to that traffic. No API key or account is required.

No analytics, advertising, or tracking services are used.

## Permissions

| Permission               | Reason                                                                         |
|--------------------------|--------------------------------------------------------------------------------|
| `INTERNET`               | Fetch weather and geocoding from Open-Meteo                                    |
| `ACCESS_NETWORK_STATE`   | Check connectivity before network requests                                     |
| `ACCESS_COARSE_LOCATION` | Approximate location for weather (optional; you can use a manual city instead) |

The live wallpaper service uses the system wallpaper binder; it does not require additional runtime permissions beyond the above.

## Contact

For questions or concerns, open an issue at [https://github.com/Attacktive/weatherd/issues](https://github.com/Attacktive/weatherd/issues).
