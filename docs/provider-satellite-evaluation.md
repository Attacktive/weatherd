# Satellite cloud-mask diagnostics for provider evaluation

Weatherd issue [#211](https://github.com/Attacktive/weatherd/issues/211) concerns false overcast scenes in Italy. The existing Android emulator and METAR/SPECI collector stays unchanged. This **offline, opt-in** companion uses independently acquired, timestamped EUMETSAT cloud-mask pixels to assess cloud-cover disagreements that airport `CAVOK` reports cannot resolve.

## Product and prerequisites

The pilot input is [EUMETSAT MSG/SEVIRI Cloud Mask, collection `EO:EUM:DAT:MSG:CLM`](https://data.eumetsat.int/search?query=EO%3AEUM%3ADAT%3AMSG%3ACLM), a full-disk geostationary product usually updated every 15 minutes. The [EUMETSAT product guide](https://user.eumetsat.int/s3/eup-strapi-media/pdf_clm_pg_6b00d53e6b.pdf) describes the GRIB Edition 2 product as classifying pixels as clear over land, clear over water, cloud, or no data. This pilot uses the published WMO cloud-mask coding (0 clear over water, 1 clear over land, 2 cloud, 3 no data) and verifies GRIB discipline/category/parameter number (3/0/7). **Verify the decoded class values and GRIB metadata against an actual downloaded sample before trusting the results.** The script records this mapping as *unverified against the individual source file*; supplying the correct collection is the operator's responsibility.

Get the actual GRIB2 cloud-mask files for the Italian study times using your own EUMETSAT Data Store access. This script does not log in, download data, or upload anything. Install optional decoding dependencies in a local environment with the native ecCodes library available:

```sh
python3 -m pip install numpy eccodes
```

Don't run the satellite extractor on the emulator; it reads the original collector's `station.json` and offline cloud-mask GRIB files. One satellite GRIB message per input file is required. If the downloaded product contains an archive, extract the individual GRIB messages without altering their contents. Missing/unsupported GRIB metadata fails closed.

## Extract one immutable station-centered observation

The original `eccodes` decoder remains the default. An alternative **Rasterio/GDAL decoder** is available for EUMETSAT's wrapped `.grb` files. It has been exercised on five original October 2026 EUMETSAT MSG CLM scans, whereas the `eccodes` path remains unverified against those files. The raster decoder reads only a station-sized window and reuses exactly the same cloud-fraction and quality-scoring functions as the default decoder.

Install the optional alternative dependencies:

```sh
python3 -m pip install numpy rasterio pyproj
```

Extract the original GRIB member from the downloaded ZIP without rewriting its bytes, then pass `--decoder rasterio` to `extract`. The alternative preserves the same observation JSON schema, immutable source SHA-256, and `summarize` command. It checks the embedded GRIB2 message even when the .grb file contains a 272-byte EUMETSAT preamble, and requires EUMETSAT center 254, product template 30, and cloud-mask parameter 3/0/7. It is optional and has no effect on app runtime or provider selection.

```sh
python3 scripts/evaluate-satellite.py extract \
  --grib /path/to/downloaded-clm.grib2 \
  --station-file captures/provider-evaluation/runs/<run-id>/station.json \
  --output captures/provider-evaluation/satellite \
  --radius-km 15
```

The extractor uses the GRIB `validityDate`/`validityTime` (UTC) and the original NOAA station latitude/longitude. It computes the cloud-pixel fraction inside a **15 km disc**, excluding unprocessed pixels. At least nine processed pixels and 80% processed coverage are required. Insufficient samples are retained with `INSUFFICIENT_COVERAGE` and **no cloud score**, not converted to clear sky. Unknown pixel codes, malformed inputs, and multiple GRIB messages are rejected.

Each output `satellite/LIRN-<epoch>-<source-hash-prefix>.json` contains the GRIB SHA-256, timestamp, source metadata, station, spatial radius, raw class counts and quality thresholds. Existing files cannot be overwritten. Multiple different observations claiming the same station and scan time cause an error rather than an arbitrary choice.

## Compare with existing captures

```sh
python3 scripts/evaluate-satellite.py summarize --output captures/provider-evaluation
```

The command reads the existing `runs/*/capture.json` and `station.json`, `run.json` and `satellite/*.json`, without changing any collector outputs or METAR match revisions. It excludes diagnostics, invalid manual locations, unsuccessful requests, invalid cloud percentages, forecasts whose valid time is over an hour from capture, and scans over 10 minutes from the capture timestamp. Distance ties favor the earlier satellite scan. It reports individual matches with source hashes, provider forecast-valid offsets, counts and **paired differences on the same capture against ItaliaMeteo**. No difference is interpreted as a formal forecast-accuracy score or a winner.

This is a **diagnostic comparison of different physical quantities**: modeled total cloud cover for a station location versus satellite cloud-mask area fraction within 15 km. The satellite may miss thin cirrus, while point forecasts can represent a larger grid cell and a different valid time. Both pixel errors and model representativeness matter. Consecutive half-hour captures, and especially captures matched to the same satellite scan, are not statistically independent. Validate spatial choice, the cloud classification, collection provenance, and multiple weather regimes before drawing conclusions. Keep temperature and METAR availability metrics separate. Never change Weatherd provider defaults solely from these outputs.

## Scope and known limitations

- EUMETSAT credentials, file retrieval, and `.grib2` extraction are **manual** at this stage. Automatic Data Store ingestion is not implemented.
- There is no new Android permission, dependency, production weather request, collection scheduler, or behavioral change.
- The optional `eccodes` decoder has unit tests with synthetic class arrays, but **has not yet been verified against a genuine downloaded EUMETSAT MSG CLM GRIB file**. Verify this before treating the product decoder as operational.
- Raw categorical cloud masks do not distinguish high/low/mid clouds or measure sky darkness. This tool helps identify strong contradictions, not solve every rendering discrepancy.

Unit tests (no ecCodes installation or satellite download required for synthetic cases):

```sh
PYTHONPATH=scripts python3 -m unittest discover -s scripts/provider_evaluation -p 'test_*.py'
```
