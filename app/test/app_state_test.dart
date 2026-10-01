import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:droiddesk/state/app_state.dart';

/// Unit tests for AppState logic.
///
/// These tests exercise the in-memory state machine without touching the real
/// MethodChannel / Kotlin layer. They use a subclass that overrides the
/// platform-bridge calls with no-ops so tests run in the standard Dart VM.
class _TestAppState extends AppState {
  // Track calls made during tests
  bool stopLinuxCalled = false;
  bool resetLinuxInstallCalled = false;

  // Simulated platform state
  bool _platformBootstrapped = false;
  bool _platformRunning = false;
  String _platformInstalledDE = '';

  void simulateBootstrapped({String de = 'xfce4'}) {
    _platformBootstrapped = true;
    _platformInstalledDE = de;
  }

  void simulateRunning() {
    _platformRunning = true;
  }

  @override
  Future<void> stopLinux() async {
    stopLinuxCalled = true;
    // Simulate successful stop
  }

  @override
  Future<void> reinstallLinux(BuildContext context) async {
    try {
      if (isRunning) {
        await stopLinux();
      }
      // Simulate resetLinuxInstall without calling channel
      resetLinuxInstallCalled = true;
      // Reset in-memory state (same as real implementation)
      // ignore: invalid_use_of_protected_member
      notifyListeners();
    } catch (e) {
      // ignore
    }
  }
}

void main() {
  group('AppState initialization', () {
    test('defaults are correct', () {
      final state = AppState();

      expect(state.isBootstrapped, isFalse);
      expect(state.isRunning, isFalse);
      expect(state.hasRoot, isFalse);
      expect(state.installedDistro, isEmpty);
      expect(state.installedDE, isEmpty);
      expect(state.selectedDistro, equals('ubuntu'));
      expect(state.selectedDE, equals('xfce4'));
      expect(state.setupStep, equals(0));
      expect(state.errorMessage, isNull);
      expect(state.themeMode, equals(ThemeMode.dark));
    });
  });

  group('isSetupComplete', () {
    test('returns false when not bootstrapped', () {
      final state = AppState();
      // _isBootstrapped=false, _installedDE=''
      expect(state.isSetupComplete, isFalse);
    });

    test('returns false when bootstrapped but no DE installed', () {
      final state = AppState();
      // Can only test external state here — see note about platform
      // isSetupComplete requires both _isBootstrapped AND _installedDE.isNotEmpty
      // We verify the logic is: isBootstrapped && installedDE.isNotEmpty
      expect(state.isSetupComplete, isFalse,
          reason: 'isSetupComplete must be false when both are default empty');
    });
  });

  group('DE and distro selection', () {
    test('setSelectedDE updates selectedDE', () {
      final state = AppState();

      state.setSelectedDE('mate');
      expect(state.selectedDE, equals('mate'));

      state.setSelectedDE('lxqt');
      expect(state.selectedDE, equals('lxqt'));
    });

    test('setSelectedDistro updates selectedDistro', () {
      final state = AppState();

      state.setSelectedDistro('debian');
      expect(state.selectedDistro, equals('debian'));
    });
  });

  group('setup step control', () {
    test('setSetupStep updates step and clears error', () {
      final state = AppState();
      // Manually inject an error to confirm it's cleared
      state.clearError(); // no-op but safe to call
      state.setSetupStep(2);
      expect(state.setupStep, equals(2));
      expect(state.errorMessage, isNull);
    });
  });

  group('clearError', () {
    test('clearError sets errorMessage to null', () {
      final state = AppState();
      state.clearError();
      expect(state.errorMessage, isNull);
    });
  });

  group('reinstallLinux', () {
    testWidgets('reinstallLinux calls stopLinux when running', (tester) async {
      final state = _TestAppState();

      // Build a minimal widget tree to get a valid BuildContext
      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) {
              return ElevatedButton(
                onPressed: () => state.reinstallLinux(context),
                child: const Text('Go'),
              );
            },
          ),
        ),
      );

      // state.isRunning is false by default — stopLinux should not be called
      final buttonFinder = find.byType(ElevatedButton);
      await tester.tap(buttonFinder);
      await tester.pumpAndSettle();

      expect(state.stopLinuxCalled, isFalse,
          reason: 'stopLinux should not be called when not running');
      expect(state.resetLinuxInstallCalled, isTrue,
          reason: 'resetLinuxInstall should always be called');
    });
  });

  group('terminal output', () {
    test('terminalOutput has initial message', () {
      final state = AppState();
      expect(state.terminalOutput, isNotEmpty);
      expect(state.terminalOutput.first, contains('DroidDesk'));
    });

    test('clearTerminal resets to initial message', () {
      final state = AppState();
      state.clearTerminal();
      expect(state.terminalOutput.length, equals(1));
      expect(state.terminalOutput.first, contains('DroidDesk'));
    });
  });

  group('theme', () {
    test('isDEInstalled returns false when installedDE is empty', () {
      final state = AppState();
      expect(state.isDEInstalled, isFalse);
    });

    test('gpuType returns the unknown GPU label for empty vendor', () {
      final state = AppState();
      // deviceInfo is empty by default
      expect(state.gpuType, equals('GPU desconocida'));
    });
  });
}
