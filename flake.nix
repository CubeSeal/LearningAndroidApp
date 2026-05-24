{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }@inputs:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ "35.0.0" ];

          # You can use "36", but see note below.
          platformVersions = [ "35" "36" ];

          includeEmulator = true;
          includeSystemImages = true;

          # This is the Play Store / Pixel-like image.
          systemImageTypes = [ "google_apis_playstore" ];

          # For x86_64 Linux desktop emulator.
          abiVersions = [ "x86_64" ];

          extraLicenses = [
            "android-sdk-license"
            "android-sdk-preview-license"
            "android-sdk-arm-dbt-license"
            "intel-android-extra-license"
            "intel-android-sysimage-license"
          ];
        };

        androidSdk = androidComposition.androidsdk;
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            (pkgs.android-studio.withSdk androidSdk)
            androidSdk
            pkgs.kotlin-language-server
          ];

          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
        };
      }
    );
}
