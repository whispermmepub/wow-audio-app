# Third-party notices

WoW Audio is intended to remain a **free, non-commercial accessibility application** for Myanmar readers, including blind and low-vision users.

## Meta MMS-TTS Burmese (`facebook/mms-tts-mya`)

- Project/model: Massively Multilingual Speech (MMS), Burmese text-to-speech model
- Publisher: Meta / Facebook AI Research
- Model page: https://huggingface.co/facebook/mms-tts-mya
- License: **Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)**
- License text: https://creativecommons.org/licenses/by-nc/4.0/legalcode

WoW Audio uses this model only for free/non-commercial accessibility purposes. The test build uses a Sherpa-compatible ONNX conversion of the same model. No endorsement by Meta is implied.

## MMS-TTS ONNX conversion

- Repository: `willwade/mms-tts-multilingual-models-onnx`
- Source: https://huggingface.co/willwade/mms-tts-multilingual-models-onnx
- Pinned revision used by WoW Audio: `d0f3eabab69a178ad836f5689044998909c88ae7`
- Model license remains **CC BY-NC 4.0**.

## sherpa-onnx

- Project: sherpa-onnx
- Copyright: k2-fsa / project contributors
- Source: https://github.com/k2-fsa/sherpa-onnx
- Runtime version: `1.13.7`
- License: **Apache License 2.0**
- License text: https://www.apache.org/licenses/LICENSE-2.0

The official Android AAR is fetched from the pinned sherpa-onnx release at build time and its SHA-256 is verified before packaging.

## Distribution condition

Do not add advertising, paid access, subscriptions, in-app purchases, or other commercial use to a build containing the MMS Burmese model without first replacing the model with one whose license permits that use or obtaining the necessary permission.
