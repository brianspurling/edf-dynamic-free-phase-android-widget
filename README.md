# EDF FreePhase Android Widget

Personal Android widget for [EDF Energy's FreePhase Dynamic](https://www.edfenergy.com/gas-and-electricity/freephase) tariff.

See [the design spec](docs/superpowers/specs/2026-05-15-android-freephase-widget-design.md) for context.

## Build

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then long-press the homescreen → Widgets → FreePhase.

## Configuration

Set the postcode and GSP region in `local.properties`:

```
FREEPHASE_POSTCODE=SW19 6AY
FREEPHASE_GSP_REGION=C
```

(`FREEPHASE_GSP_REGION` is one letter A–P from the Kraken `/v1/industry/grid-supply-points/?postcode=…` endpoint.)
