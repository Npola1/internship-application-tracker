#!/usr/bin/env python3
"""Writes the four non-secret AWS deployment outputs into the Android Gradle settings."""
import json
import os
from pathlib import Path

PROPERTIES = (
    ("trackerApiUrl", "ApiBaseUrl"),
    ("trackerCognitoDomain", "CognitoHostedUiBase"),
    ("trackerCognitoClientId", "CognitoClientId"),
    ("trackerAssetBaseUrl", "CloudFrontAssetsUrl"),
)

def write_config(outputs, path):
    required = [output for _, output in PROPERTIES]
    if any(not outputs.get(key) for key in required):
        raise ValueError("Missing deployment outputs: " + ", ".join(key for key in required if not outputs.get(key)))
    lines = path.read_text().splitlines()
    for prop, output in PROPERTIES:
        replacement = prop + "=" + outputs[output]
        if any(line.startswith(prop + "=") for line in lines):
            lines = [replacement if line.startswith(prop + "=") else line for line in lines]
        else:
            lines.append(replacement)
    path.write_text("\n".join(lines) + "\n")

def main():
    raw = os.environ.get("TRACKER_OUTPUTS", "")
    if not raw:
        raise SystemExit("TRACKER_OUTPUTS must contain the JSON stack Outputs array")
    outputs = {item["OutputKey"]: item["OutputValue"] for item in json.loads(raw)}
    try:
        write_config(outputs, Path(__file__).resolve().parents[1] / "android/gradle.properties")
    except ValueError as error:
        raise SystemExit(str(error))
    print("Configured Android API endpoint, Cognito login and CloudFront URLs.")

if __name__ == '__main__':
    main()
