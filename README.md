# RainBird Binding für openHAB (WaterBird)

<img width="200" height="200" alt="WaterBird" src="https://github.com/user-attachments/assets/b6e9e34c-eb24-4b19-b3b4-fa17cd56cab0" />

Inofficial Binding for openHAB for RainBird controllers.

## Contribution
[allenporter/pyrainbird](https://github.com/allenporter/pyrainbird) - Thanks for this great work.

## Features

* Connects RainBird controllers in your LAN (only tested with ESP-TM2)
* Automatic discovery of the controller as a **Bridge Thing**
* Reads controller information:
  * Number of programs, basic schedule summary, controller time
  * Rain delay, seasonal adjust, active program / active station
* **Dynamic channels for irrigation zones**
  * For each zone: *Active*, *Duration* and *Remaining*
* Start/stop individual zones directly from openHAB

## Supported Things

| Thing Type ID      | Name                 | Description                                                    |
|--------------------|----------------------|----------------------------------------------------------------|
| `rainbird:bridge`  | Rain Bird Controller | Represents a Rain Bird controller                              |

## Discovery

The LNK WiFi stick is discovered automatically via **SCAN** button on the local network or you can enter IP
It appears in the Inbox as **Rain Bird Controller (Local)**
After moving from inbox you have to enter your password.

*Note: Trigger is "RainBird.localdomain" as hostname which is given by LNK2 WiFi module - other controllers may not be detected automatically.*

## Bridge Configuration

The exact field names may differ slightly by openHAB version, but conceptually you configure:

| Parameter           | Required | Description                                                      |
|---------------------|----------|------------------------------------------------------------------|
| IP / Host           | yes (for manual creation) | IP address of the LNK WiFi stick in your LAN    |
| Password            | yes      | Device password as printed on the LNK stick                      |
| Poll interval       | optional | Status polling interval in seconds (e.g. `30`)                   |

After the bridge goes ONLINE, the binding queries the controller, detects how many zones are configured and **automatically creates channels** for each one.

---

## Channels

### Controller / Bridge Channels

These channels are provided directly by the `rainbird:bridge` Thing:

| Channel ID        | Item Type          | Description |
|-------------------|--------------------|-------------|
| `programCount`    | Number             | Number of irrigation programs configured in the controller |
| `scheduleSummary` | String             | Text summary of the current irrigation schedule (if available) |
| `controllerTime`  | DateTime           | Current controller time |
| `rainDelay`       | Number             | Remaining rain delay (rain pause) |
| `seasonalAdjust`  | Number (0–100)     | Seasonal adjustment factor in percent |
| `activeStation`   | Number             | Currently running station / zone number (`0` = none) |
| `lastPoll`        | DateTime           | Timestamp of the last successful status poll |
| `programSelector` | String             | Start/stop a stored program (depending on firmware support) |
| `zoneCount`       | Number             | Number of zones detected on the controller |

### Dynamic Zone Channels

Once the bridge is ONLINE and the controller has reported its configuration, the binding **dynamically creates three channels per zone** directly on the bridge:

For zone `X`:

| Channel ID             | Item Type | Description |
|------------------------|-----------|-------------|
| `zoneActiveX`          | Switch    | `ON` starts zone `X` with the configured duration, `OFF` stops watering immediately |
| `zoneDurationX`        | Number    | Requested watering duration for zone `X` in **seconds** |
| `zoneRemainingX`       | Number    | Remaining watering time for zone `X` in **seconds** |

Example channel IDs produced at runtime:

* `zoneActive1`, `zoneDuration1`, `zoneRemaining1`
* `zoneActive2`, `zoneDuration2`, `zoneRemaining2`
* …
* `zoneActive6`, `zoneDuration6`, `zoneRemaining6`

These channels appear automatically after the initial controller poll – there is no need to define them by hand.

Internally the binding maintains the duration in **minutes** for compatibility with the Rain Bird API, but exposes a **seconds-based Number item** so that both:
* Minute-based sliders in the openHAB UI  
* second-accurate values for HomeKit / rules
can be supported.

*Note: Slider needs to be done as custom widget and is not implemented*

## Debug
* `log:set DEBUG org.openhab.binding.rainbird`
* `log:set DEBUG org.openhab.binding.rainbird.internal`
* `log:tail` or `log:tail | grep -i rainbird`

## not implemented
* option to skip zones
* option to de/activate a zone
* manually add/remove zone from controller

## known limitations
* This is an experimental binding [BETA]
* may not work on other controllers than LNK2
* debugging is not guaranteed

## Donations
BTC: 3EaR4sURisXD2pjCToscZAwgFTNXZNXj95
