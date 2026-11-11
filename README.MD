# Lossless Sources v6

A lossless audio plugin for **Spotube 6** (the new Kotlin plugin system). It plays each track as FLAC from
[Monochrome](https://monochrome.tf), or from a Tidal hifi-api server when one is working.

It's a port of [kipavy/spotube-plugin-lossless-sources](https://github.com/kipavy/spotube-plugin-lossless-sources)
(MIT), which only runs on Spotube 5.x. It uses the same hourly-checked list of working servers, published by that project.

## Install

In Spotube: Settings → Plugins → Install → paste `https://github.com/HughJazz98/spotube-plugin-lossless-v6` → Download.
Then pick **Lossless Sources v6** as the Audio plugin and set **Playback → streaming format → FLAC**.

## Limits

Lossless only: a track that no lossless source has won't play with this plugin selected (switch back to the
YouTube audio plugin for those).
