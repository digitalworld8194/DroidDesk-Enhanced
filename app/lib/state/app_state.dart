import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:droiddesk/l10n/app_strings.dart';

/// Central state management for the entire DroidDesk app.
class AppState extends ChangeNotifier with WidgetsBindingObserver {
  // ── Theme State ──
  ThemeMode _themeMode = ThemeMode.dark;

  // ── Setup State ──
  bool _isBootstrapped = false;
  bool _isRunning = false;
  bool _hasRoot = false;
  String _installedDistro = '';
  String _installedDE = '';
  String _selectedDistro = 'ubuntu';
  String _selectedDE = 'xfce4';
  int _setupStep = 0; // 0=welcome, 1=distro, 2=de, 3=install, 4=done

  // ── Download/Install Progress ──
  double _downloadProgress = 0.0;
  String _downloadStatus = '';
  double _extractProgress = 0.0;
  String _extractStatus = '';
  bool _isDownloading = false;
  bool _isExtracting = false;
  bool _isInstallingDE = false;
  String? _statusMessage;
  String _setupLog = '';
  Map<String, bool> _optionalApps = {};
  String? _installingOptionalApp;
  double _optionalInstallProgress = 0.0;
  String _optionalInstallStatus = '';
  String _optionalInstallLog = '';
  bool _isProotTerminal = false;

  // Terminal history. The PRoot container has its own buffer so its output
  // (or failure) never mixes into the native Termux terminal.
  final List<String> _terminalOutput = [l10n.terminalWelcome];
  final List<String> _containerOutput = [];
  List<String> get terminalOutput =>
      _isProotTerminal ? _containerOutput : _terminalOutput;

  // ── Device Info ──
  Map<String, dynamic> _deviceInfo = {};

  // ── Error State ──
  String? _errorMessage;

  // ── Getters ──
  ThemeMode get themeMode => _themeMode;
  bool get isDarkMode => DroidTheme.isDark;
  bool get isBootstrapped => _isBootstrapped;
  bool get isRunning => _isRunning;
  bool get hasRoot => _hasRoot;
  String get installedDistro => _installedDistro;
  String get installedDE => _installedDE;
  String get selectedDistro => _selectedDistro;
  String get selectedDE => _selectedDE;
  int get setupStep => _setupStep;
  double get downloadProgress => _downloadProgress;
  String get downloadStatus => _downloadStatus;
  double get extractProgress => _extractProgress;
  String get extractStatus => _extractStatus;
  String? get statusMessage => _statusMessage;
  String get setupLog => _setupLog;
  bool get isDownloading => _isDownloading;
  bool get isExtracting => _isExtracting;
  bool get isInstallingDE => _isInstallingDE;
  Map<String, dynamic> get deviceInfo => _deviceInfo;
  String? get errorMessage => _errorMessage;
  Map<String, bool> get optionalApps => _optionalApps;
  String? get installingOptionalApp => _installingOptionalApp;
  double get optionalInstallProgress => _optionalInstallProgress;
  String get optionalInstallStatus => _optionalInstallStatus;
  String get optionalInstallLog => _optionalInstallLog;
  bool get isProotTerminal => _isProotTerminal;

  bool get isSetupComplete => _isBootstrapped && _installedDE.isNotEmpty;
  bool get isDEInstalled => _installedDE.isNotEmpty;

  String get gpuType {
    final vendor = _deviceInfo['gpuVendor']?.toString() ?? '';
    if (vendor.contains('adreno')) return 'Adreno (Snapdragon)';
    if (vendor.contains('mali')) return 'Mali (MediaTek/Exynos)';
    if (vendor.contains('powervr')) return 'PowerVR';
    return l10n.unknownGpu;
  }

  // ── Initialization ──

  /// Re-reads the real desktop state when DroidDesk returns to the
  /// foreground (e.g. from the desktop), so the card never stays stale.
  @override
  void didChangeAppLifecycleState(AppLifecycleState lifecycle) {
    if (lifecycle == AppLifecycleState.resumed) refreshStatus();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<void> initialize() async {
    WidgetsBinding.instance.addObserver(this);
    await _loadThemeMode();

    // Set up progress callbacks
    DroidDeskPlatform.onDownloadProgress = (progress, status) {
      _downloadProgress = progress;
      _downloadStatus = status;
      if (progress < 0) {
        _isDownloading = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        _isDownloading = false;
      }
      notifyListeners();
    };

    DroidDeskPlatform.onExtractProgress = (progress, status) {
      _extractProgress = progress;
      _extractStatus = status;
      if (progress < 0) {
        _isExtracting = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        _isExtracting = false;
        refreshStatus();
      }
      notifyListeners();
    };

    DroidDeskPlatform.onInstallProgress = (progress, status) {
      _extractProgress = progress; // reusing extract progress state for UI
      _extractStatus = status;
      _statusMessage = status;
      _isInstallingDE = progress >= 0 && progress < 1.0;
      if (progress < 0) {
        _isExtracting = false;
        _isInstallingDE = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        _isExtracting = false;
        _isInstallingDE = false;
        refreshStatus();
      }
      notifyListeners();
    };

    DroidDeskPlatform.onContainerOutput = (text) {
      _appendLines(_containerOutput, text.replaceAll(RegExp(r'.*\r(?!\n)'), ''));
      notifyListeners();
    };

    DroidDeskPlatform.onTerminalOutput = (text) {
      if (_terminalOutput.isEmpty) _terminalOutput.add('');

      final cleanedText = text.replaceAll(RegExp(r'.*\r(?!\n)'), '');
      if (_setupStep == 3) {
        _setupLog += cleanedText;
        if (_setupLog.length > 20000) {
          _setupLog = _setupLog.substring(_setupLog.length - 20000);
        }
      }
      if (_installingOptionalApp != null) {
        _optionalInstallLog += cleanedText;
        if (_optionalInstallLog.length > 20000) {
          _optionalInstallLog = _optionalInstallLog.substring(
            _optionalInstallLog.length - 20000,
          );
        }
      }
      final lines = cleanedText.split('\n');

      for (int i = 0; i < lines.length; i++) {
        if (i == 0) {
          _terminalOutput[_terminalOutput.length - 1] += lines[i];
        } else {
          _terminalOutput.add(lines[i]);
        }
      }
      notifyListeners();
    };

    DroidDeskPlatform.onOptionalInstallProgress = (progress, status) {
      _optionalInstallProgress = progress.clamp(0.0, 1.0);
      _optionalInstallStatus = status;
      notifyListeners();
    };

    await refreshStatus();
    await loadDeviceInfo();
  }

  // ── Theme Control ──

  Future<void> _loadThemeMode() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final savedMode = prefs.getString('theme_mode');
      if (savedMode == 'light') {
        _themeMode = ThemeMode.light;
      } else if (savedMode == 'system') {
        _themeMode = ThemeMode.system;
      } else {
        _themeMode = ThemeMode.dark;
      }
      DroidTheme.currentThemeMode = _themeMode;
      notifyListeners();
      await DroidDeskPlatform.updateStatusBarTheme(!isDarkMode);
    } catch (_) {
      // Default to dark theme if preferences fail
    }
  }

  Future<void> setThemeMode(ThemeMode mode) async {
    _themeMode = mode;
    DroidTheme.currentThemeMode = mode;
    notifyListeners();
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('theme_mode', mode.name);
      await DroidDeskPlatform.updateStatusBarTheme(!isDarkMode);
    } catch (_) {}
  }

  Future<void> toggleThemeMode() async {
    if (_themeMode == ThemeMode.dark) {
      await setThemeMode(ThemeMode.light);
    } else {
      await setThemeMode(ThemeMode.dark);
    }
  }

  Future<void> refreshStatus() async {
    try {
      final status = await DroidDeskPlatform.getRuntimeStatus();
      _isBootstrapped = status['isBootstrapped'] == true;
      _isRunning = status['isRunning'] == true;
      _hasRoot = status['hasRoot'] == true;
      _installedDistro = status['distro']?.toString() ?? '';
      _installedDE = status['installedDE']?.toString() ?? '';

      if (_installedDE.isNotEmpty) {
        await refreshOptionalApps();
      }

      notifyListeners();
    } catch (e) {
      // Unknown state is never shown as "Escritorio activo".
      _isRunning = false;
      _errorMessage = l10n.runtimeStatusFailed(_describeError(e));
      notifyListeners();
    }
  }

  Future<void> loadDeviceInfo() async {
    try {
      _deviceInfo = await DroidDeskPlatform.getDeviceInfo();
      notifyListeners();
    } catch (e) {
      // Non-fatal — continue without device info
    }
  }

  // ── Setup Flow ──

  void setSelectedDistro(String distro) {
    _selectedDistro = distro;
    notifyListeners();
  }

  void setSelectedDE(String de) {
    _selectedDE = de;
    notifyListeners();
  }

  void setSetupStep(int step) {
    _setupStep = step;
    _errorMessage = null;
    notifyListeners();
  }

  Future<bool> detectRootForSetup() async {
    _hasRoot = await DroidDeskPlatform.checkRoot();
    notifyListeners();
    return _hasRoot;
  }

  /// Main setup entry point. [useRoot] records confirmation from the
  /// rooted-device dialog.
  Future<void> runSetup({bool? useRoot}) async {
    try {
      _errorMessage = null;
      _setupLog = '';
      _setupStep = 3;
      notifyListeners();

      // Detect root and choose path
      final rootAvailable = await DroidDeskPlatform.checkRoot();
      _hasRoot = rootAvailable && (useRoot ?? true);
      notifyListeners();

      if (_hasRoot) {
        await _runChrootSetup();
      } else {
        await _runNativeSetup();
      }

      _setupStep = 4;
      notifyListeners();
    } catch (e) {
      _errorMessage = l10n.setupFailed(_describeError(e));
      _isDownloading = false;
      _isExtracting = false;
      _isInstallingDE = false;
      notifyListeners();
    }
  }

  Future<void> _runNativeSetup() async {
    _isExtracting = true;
    _extractProgress = 0.0;
    _extractStatus = l10n.extractingTermuxBootstrap;
    _statusMessage = _extractStatus;
    notifyListeners();
    await DroidDeskPlatform.setupBootstrap();

    _extractProgress = 0.08;
    _extractStatus = l10n.bootstrapReady;
    _statusMessage = _extractStatus;
    _isInstallingDE = true;
    notifyListeners();
    final installed = await DroidDeskPlatform.installDesktopNative(
      de: _selectedDE,
    );
    if (!installed) {
      throw StateError(l10n.nativeInstallFailed);
    }
    _isExtracting = false;
    _isInstallingDE = false;

    await refreshStatus();
  }

  Future<void> _runChrootSetup() async {
    _statusMessage = l10n.downloadingUbuntuRootfs;
    _isDownloading = true;
    _downloadProgress = 0.0;
    notifyListeners();
    if (!await DroidDeskPlatform.downloadRootfs(_selectedDistro)) {
      throw StateError(l10n.ubuntuDownloadFailed);
    }

    _isDownloading = false;
    _isExtracting = true;
    _extractProgress = 0.0;
    _statusMessage = l10n.extractingRootfs;
    notifyListeners();
    if (!await DroidDeskPlatform.extractRootfs()) {
      throw StateError(l10n.ubuntuExtractionFailed);
    }

    _statusMessage = l10n.installingDesktopEnvironmentLong;
    _isInstallingDE = true;
    notifyListeners();
    if (!await DroidDeskPlatform.installDesktopEnvironment(_selectedDE)) {
      throw StateError(l10n.essentialsInstallFailed);
    }
    _isExtracting = false;
    _isInstallingDE = false;

    await refreshStatus();
  }

  Future<void> runExtraction() async {
    // Handled inside runSetup for chroot mode.
    // Kept for API compatibility.
    _extractProgress = 1.0;
    _extractStatus = l10n.extractionHandledBySetup;
    notifyListeners();
  }

  Future<void> installDesktopEnvironment() async {
    try {
      _isExtracting = true;
      _extractProgress = 0.0;
      _statusMessage = l10n.installingDesktopEnvironment;
      _errorMessage = null;
      notifyListeners();

      if (_hasRoot) {
        if (!await DroidDeskPlatform.installDesktopEnvironment(_selectedDE)) {
          throw StateError(l10n.essentialsInstallFailed);
        }
      } else {
        final installed = await DroidDeskPlatform.installDesktopNative(
          de: _selectedDE,
        );
        if (!installed) {
          throw StateError(l10n.nativeInstallFailed);
        }
      }

      _isExtracting = false;
      _isInstallingDE = false;
      await refreshStatus();
    } catch (e) {
      _errorMessage = l10n.installationFailed(_describeError(e));
      _isExtracting = false;
      _isInstallingDE = false;
      notifyListeners();
    }
  }

  // ── Optional applications ──

  Future<void> refreshOptionalApps() async {
    try {
      _optionalApps = await DroidDeskPlatform.getOptionalApps();
      notifyListeners();
    } catch (_) {
      // The desktop remains usable even if package status cannot be queried.
    }
  }

  Future<bool> installOptionalApp(String appId) async {
    if (_installingOptionalApp != null) return false;
    _installingOptionalApp = appId;
    _optionalInstallProgress = 0.0;
    _optionalInstallStatus = l10n.preparingInstallation;
    _optionalInstallLog = '';
    notifyListeners();

    try {
      final installed = await DroidDeskPlatform.installOptionalApp(appId);
      await refreshOptionalApps();
      return installed;
    } finally {
      _installingOptionalApp = null;
      notifyListeners();
    }
  }

  // ── Session Control ──

  Future<void> startLinux({
    String mode = 'x11',
    int width = 1920,
    int height = 1080,
  }) async {
    try {
      _errorMessage = null;
      final started = await DroidDeskPlatform.startLinux(
        de: _selectedDE,
        mode: mode,
        width: width,
        height: height,
      );
      if (!started) {
        throw StateError(l10n.runtimeNotReady);
      }
      // "Escritorio activo" comes from the runtime's real detection.
      await refreshStatus();
    } catch (e) {
      _errorMessage = l10n.startFailed(_describeError(e));
      notifyListeners();
    }
  }

  Future<void> launchDesktopActivity() async {
    try {
      await DroidDeskPlatform.launchDesktopActivity();
    } catch (e) {
      _errorMessage = l10n.launchDesktopFailed(_describeError(e));
      notifyListeners();
    }
  }

  Future<void> stopLinux() async {
    try {
      await DroidDeskPlatform.stopLinux();
      _isRunning = false;
      notifyListeners();
    } catch (e) {
      _errorMessage = l10n.stopFailed(_describeError(e));
      notifyListeners();
    }
  }

  Future<void> reinstallLinux(BuildContext context) async {
    try {
      // Stop the session if running
      if (_isRunning) {
        await stopLinux();
      }
      // Reset installed state via platform bridge
      await DroidDeskPlatform.resetLinuxInstall();
      // Navigate back to setup flow
      _isBootstrapped = false;
      _installedDE = '';
      _installedDistro = '';
      _setupStep = 0;
      notifyListeners();
    } catch (e) {
      _errorMessage = l10n.reinstallFailed(_describeError(e));
      notifyListeners();
    }
  }

  Future<String> executeCommand(String command) async {
    try {
      if (_isProotTerminal) {
        _containerOutput.add('\$ $command\n');
        notifyListeners();
        if (!await DroidDeskPlatform.containerInput(command)) {
          _appendLines(_containerOutput, l10n.containerClosed);
          notifyListeners();
        }
        return '';
      }
      _terminalOutput.add('\$ $command\n');
      notifyListeners();
      return await DroidDeskPlatform.executeCommand(command);
    } catch (e) {
      return "Error executing command: $e";
    }
  }

  void useNativeTerminal() {
    _isProotTerminal = false;
    notifyListeners();
  }

  /// Opens the Debian container in its own process and terminal buffer; the
  /// native terminal, XFCE and D-Bus are not touched whatever PRoot does.
  Future<void> startDebianShell() async {
    _isProotTerminal = true;
    _containerOutput
      ..clear()
      ..add('');
    notifyListeners();
    try {
      final exit = await DroidDeskPlatform.startContainerShell();
      _appendLines(_containerOutput, l10n.containerExited(exit));
    } catch (e) {
      _appendLines(_containerOutput, l10n.containerExited(null));
    }
    notifyListeners();
  }

  static void _appendLines(List<String> buffer, String text) {
    if (buffer.isEmpty) buffer.add('');
    final lines = text.split('\n');
    buffer[buffer.length - 1] += lines.first;
    buffer.addAll(lines.skip(1));
  }

  void appendTerminalOutput(String output) {
    final buffer = terminalOutput;
    if (buffer.isEmpty) buffer.add('');
    buffer[buffer.length - 1] += output;
    notifyListeners();
  }

  /// Error text without Dart's English "Bad state: " prefix for our own errors.
  static String _describeError(Object error) =>
      error is StateError ? error.message : '$error';

  void clearTerminal() {
    if (_isProotTerminal) {
      _containerOutput
        ..clear()
        ..add('');
    } else {
      _terminalOutput.clear();
      _terminalOutput.add(l10n.terminalWelcome);
    }
    notifyListeners();
  }

  Future<void> interruptCommand() async {
    try {
      if (_isProotTerminal) {
        await DroidDeskPlatform.stopContainerShell();
        return;
      }
      await DroidDeskPlatform.interruptCommand();
    } catch (e) {
      debugPrint("Error interrupting command: $e");
    }
  }

  void clearError() {
    _errorMessage = null;
    notifyListeners();
  }
}
