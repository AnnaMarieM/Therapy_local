#!/usr/bin/env bash
# qa/to-wav.sh input.m4a|mp3|wav qa/eval-audio/cs_name.wav   -> 16 kHz mono PCM16 wav for qa/asr-eval.sh
set -euo pipefail
gst-launch-1.0 -q filesrc location="$1" ! decodebin ! audioconvert ! audioresample ! audio/x-raw,rate=16000,channels=1,format=S16LE ! wavenc ! filesink location="$2"
