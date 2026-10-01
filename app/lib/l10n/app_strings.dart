import 'package:flutter/widgets.dart';

import 'app_strings_es.dart';

/// User-visible texts of the Flutter UI.
///
/// Every language implements this class (see [AppStringsEs]). To add a
/// language, create `app_strings_<code>.dart` implementing [AppStrings],
/// list its [Locale] in [supportedLocales] and return it from [forLocale].
/// Spanish is the default and fallback language.
abstract class AppStrings {
  const AppStrings();

  static const Locale defaultLocale = Locale('es');
  static const List<Locale> supportedLocales = [defaultLocale];
  static const LocalizationsDelegate<AppStrings> delegate = _AppStringsDelegate();

  /// Strings for the active locale, also usable outside the widget tree
  /// (state classes, helpers without a BuildContext).
  static AppStrings current = forLocale(defaultLocale);

  static AppStrings forLocale(Locale locale) {
    switch (locale.languageCode) {
      default:
        return const AppStringsEs();
    }
  }

  // ── Common ──
  String get back;
  String get next;
  String get cancel;
  String get retry;
  String get goBack;
  String get install;
  String get delete;
  String get restore;
  String get switchToLightTheme;
  String get switchToDarkTheme;
  String themeToggleTooltip(bool isDark) => isDark ? switchToLightTheme : switchToDarkTheme;

  // ── Welcome ──
  String get welcomeTagline;
  String get welcomeHighlights;
  String get featureContainerized;
  String get featureRootOptional;
  String get featureLinuxDesktop;
  String get featureLocalExecution;
  String get setUpDesktopEssentials;

  // ── Distro picker ──
  String get chooseLinux;
  String get chooseLinuxSubtitle;
  String get recommendedBadge;
  String distroDescription(String id);
  String downloadSize(String size);

  // ── Desktop picker ──
  String get chooseDesktop;
  String get chooseDesktopSubtitle;
  String get bestBadge;
  String get installEssentials;
  String desktopDescription(String id);

  // ── Setup progress ──
  String get lowStorageTitle;
  String lowStorageMessage(int freeMb);
  String get continueAnyway;
  String get rootDetectedTitle;
  String get rootDetectedMessage;
  String get continueWithRoot;
  String get launchDroidDesk;
  String get stepBootstrap;
  String get stepConfigureRepositories;
  String get stepInstallEssentials;
  String get stepFinalizeEssentials;
  String get stepRootConfirmed;
  String get stepDownloadUbuntuRootfs;
  String get stepInstallNativePackages;
  String get stepExtractRootfs;
  String get stepConfigureDesktop;
  String get stepConfigureLinux;
  String get phaseSetupFailed;
  String get phaseDownloading;
  String get preparingDownload;
  String get phasePreparingRuntime;
  String get phaseInstallingNativeLinux;
  String get preparingNativeTermux;
  String get phaseInstallingDesktop;
  String get phaseExtracting;
  String get extractingFilesystem;
  String get phaseSetupComplete;
  String get setupCompleteMessage;
  String get phaseSettingUp;
  String get initializing;

  // ── Desktop install screen ──
  String get installationComplete;
  String get installationFailedTitle;
  String get configuringWorkstation;
  String get linuxEnvironmentReady;
  String get downloadingSystemPackages;
  String get extractingPackages;
  String get returnToHome;
  String get supportOpenSource;

  // ── Home ──
  String get desktopRunning;
  String get ready;
  String get quickActions;
  String installDesktop(String desktop);
  String get installDesktopSubtitle;
  String get returnToDesktop;
  String runningInBackground(String desktop);
  String get stopServer;
  String get launchDesktop;
  String get shutdownLinux;
  String startDesktopEnvironment(String desktop);
  String get noDesktopInstalled;
  String get terminal;
  String terminalSubtitle({required bool chroot});
  String get debianShell;
  String get debianShellSubtitle;
  String get addApplications;
  String get addApplicationsSubtitle;
  String get systemSection;
  String get infoDistribution;
  String get infoDesktop;
  String get infoRenderer;
  String get infoDevice;
  String get infoStorageFree;
  String get automatic;
  String get notAvailable;
  String get desktopActive;
  String get desktopIdle;
  String get tapLaunchDesktop;
  String get termuxNative;

  // ── Settings sheet ──
  String get settings;
  String get appTheme;
  String get systemDefaultTheme;
  String get darkTheme;
  String get lightTheme;
  String get themeDark;
  String get themeLight;
  String get themeSystem;
  String get batteryOptimization;
  String get batteryOptimizationSubtitle;
  String get setDefaultLauncher;
  String get setDefaultLauncherSubtitle;
  String get stopDefaultLauncher;
  String get stopDefaultLauncherSubtitle;
  String get desktopTools;
  String get desktopToolsSubtitle;
  String get emergencyExit;
  String get androidSettings;
  String get androidSettingsSubtitle;
  String get changeHomeApp;
  String get changeHomeAppSubtitle;
  String get returnToSamsungHome;
  String get returnToSamsungHomeSubtitle;
  String get reinstallLinux;
  String get reinstallLinuxSubtitle;
  String get reinstallLinuxTitle;
  String get reinstallLinuxMessage;
  String get reinstall;
  String get changeLauncherTitle;
  String get changeLauncherMessage;
  String get changeLauncher;

  // ── Terminal ──
  String get terminalWelcome;
  String get commandInterrupted;
  String get interruptCommand;
  String get enterCommand;
  String get terminalModeCompat;
  String get terminalModeNative;

  // ── App catalog ──
  String catalogAppDescription(String id);
  String preparingPackage(String package);
  String packageInstallCancelled(String package);
  String packageInstalled(String package);
  String packageInstallFailed(String package);
  String get cancellingInstallation;
  String removePackageTitle(String package);
  String get removePackageMessage;
  String get uninstall;
  String get preparingRemoval;
  String packageRemoved(String package);
  String get removalFailed;
  String get linuxAppStore;
  String get tabFeatured;
  String get tabBrowse;
  String get tabInstalled;
  String get debianCompatibility;
  String get debianCompatibilityDescription;
  String get popularLinuxApps;
  String get handPickedApps;
  String get searchPackages;
  String get noMatchingPackages;
  String get noPackagesInstalled;
  String get linuxPackage;
  String get guiApp;
  String get sectionSystem;
  String get cancelling;
  String get cancelInstallation;

  // ── Desktop tools ──
  String get dockUpdated;
  String get dockUpdateFailed;
  String get snapshotCreated;
  String snapshotFailed(String error);
  String get restoreSnapshotTitle;
  String get restoreSnapshotMessage;
  String get snapshotRestored;
  String get restoreFailed;
  String restoreFailedWithError(String error);
  String get deleteSnapshotTitle;
  String get tabDock;
  String get tabBackups;
  String get pinnedSection;
  String get addAndroidAppSection;
  String get searchInstalledApps;
  String get createSnapshot;
  String get createSnapshotDescription;
  String get snapshotsSection;
  String get noSnapshots;

  // ── App state (status and errors) ──
  String get unknownGpu;
  String runtimeStatusFailed(String error);
  String setupFailed(String error);
  String get extractingTermuxBootstrap;
  String get bootstrapReady;
  String get nativeInstallFailed;
  String get downloadingUbuntuRootfs;
  String get ubuntuDownloadFailed;
  String get extractingRootfs;
  String get ubuntuExtractionFailed;
  String get installingDesktopEnvironmentLong;
  String get essentialsInstallFailed;
  String get extractionHandledBySetup;
  String get installingDesktopEnvironment;
  String installationFailed(String error);
  String get preparingInstallation;
  String get runtimeNotReady;
  String startFailed(String error);
  String launchDesktopFailed(String error);
  String stopFailed(String error);
  String reinstallFailed(String error);
}

/// Shorthand for the active strings: `l10n.chooseLinux`.
AppStrings get l10n => AppStrings.current;

class _AppStringsDelegate extends LocalizationsDelegate<AppStrings> {
  const _AppStringsDelegate();

  @override
  bool isSupported(Locale locale) => AppStrings.supportedLocales
      .any((supported) => supported.languageCode == locale.languageCode);

  @override
  Future<AppStrings> load(Locale locale) async =>
      AppStrings.current = AppStrings.forLocale(locale);

  @override
  bool shouldReload(_AppStringsDelegate old) => false;
}
