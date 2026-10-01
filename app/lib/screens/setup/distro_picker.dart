import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:droiddesk/screens/setup/de_picker.dart';
import 'package:droiddesk/l10n/app_strings.dart';

/// Distro selection screen — step 1 of setup wizard.
class DistroPickerScreen extends StatelessWidget {
  const DistroPickerScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();

    return Scaffold(
      body: Container(
        decoration: BoxDecoration(gradient: DroidTheme.backgroundGradient),
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const SizedBox(height: 16),

                // ── Header & Theme Toggle ──
                Row(
                  children: [
                    Expanded(child: _buildStepIndicator(1, 3)),
                    const SizedBox(width: 12),
                    IconButton(
                      onPressed: () => state.toggleThemeMode(),
                      tooltip: l10n.themeToggleTooltip(state.isDarkMode),
                      icon: Icon(
                        state.isDarkMode
                            ? Icons.light_mode_rounded
                            : Icons.dark_mode_rounded,
                        color: DroidTheme.textMuted,
                        size: 20,
                      ),
                      padding: EdgeInsets.zero,
                      constraints: const BoxConstraints(
                        minWidth: 32,
                        minHeight: 32,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 32),

                Text(l10n.chooseLinux, style: DroidTheme.headingXl)
                    .animate()
                    .fadeIn(duration: 400.ms)
                    .slideX(begin: -0.1, duration: 400.ms),

                const SizedBox(height: 8),
                Text(
                  l10n.chooseLinuxSubtitle,
                  style: DroidTheme.bodyMd,
                )
                    .animate()
                    .fadeIn(delay: 100.ms, duration: 400.ms),

                const SizedBox(height: 32),

                // ── Distro Cards ──
                Expanded(
                  child: ListView(
                    children: [
                      _DistroCard(
                        id: 'ubuntu',
                        name: 'Ubuntu 24.04 LTS',
                        description: l10n.distroDescription('ubuntu'),
                        size: l10n.downloadSize('~350 MB'),
                        color: DroidTheme.ubuntuColor,
                        icon: Icons.circle,
                        recommended: true,
                        selected: state.selectedDistro == 'ubuntu',
                        onTap: () => state.setSelectedDistro('ubuntu'),
                      ),
                      const SizedBox(height: 12),
                      _DistroCard(
                        id: 'alpine',
                        name: 'Alpine Linux 3.20',
                        description: l10n.distroDescription('alpine'),
                        size: l10n.downloadSize('~3 MB'),
                        color: DroidTheme.alpineColor,
                        icon: Icons.diamond_outlined,
                        recommended: false,
                        selected: state.selectedDistro == 'alpine',
                        onTap: () => state.setSelectedDistro('alpine'),
                      ),
                      const SizedBox(height: 12),
                      _DistroCard(
                        id: 'kali',
                        name: 'Kali Linux',
                        description: l10n.distroDescription('kali'),
                        size: l10n.downloadSize('~500 MB'),
                        color: DroidTheme.kaliColor,
                        icon: Icons.shield_outlined,
                        recommended: false,
                        selected: state.selectedDistro == 'kali',
                        onTap: () => state.setSelectedDistro('kali'),
                      ),
                    ]
                        .animate(interval: 80.ms)
                        .fadeIn(delay: 200.ms, duration: 400.ms)
                        .slideY(begin: 0.1, duration: 400.ms),
                  ),
                ),

                // ── Navigation ──
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 24),
                  child: Row(
                    children: [
                      OutlinedButton(
                        onPressed: () => Navigator.pop(context),
                        child: Text(l10n.back),
                      ),
                      const Spacer(),
                      ElevatedButton(
                        onPressed: () {
                          Navigator.of(context).push(
                            PageRouteBuilder(
                              pageBuilder: (context, animation, secondaryAnimation) => const DEPickerScreen(),
                              transitionsBuilder: (context, animation, secondaryAnimation, child) {
                                return FadeTransition(
                                  opacity: animation,
                                  child: child,
                                );
                              },
                              transitionDuration: const Duration(milliseconds: 300),
                            ),
                          );
                        },
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(l10n.next),
                            SizedBox(width: 4),
                            Icon(Icons.arrow_forward_rounded, size: 18),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildStepIndicator(int current, int total) {
    return Row(
      children: List.generate(total, (i) {
        final isActive = i < current;
        final isCurrent = i == current - 1;
        return Expanded(
          child: Container(
            height: 3,
            margin: const EdgeInsets.symmetric(horizontal: 2),
            decoration: BoxDecoration(
              color: isCurrent
                  ? DroidTheme.primary
                  : isActive
                      ? DroidTheme.primary.withValues(alpha: 0.5)
                      : DroidTheme.surfaceBorder,
              borderRadius: BorderRadius.circular(2),
            ),
          ),
        );
      }),
    );
  }
}

class _DistroCard extends StatelessWidget {
  final String id;
  final String name;
  final String description;
  final String size;
  final Color color;
  final IconData icon;
  final bool recommended;
  final bool selected;
  final VoidCallback onTap;

  const _DistroCard({
    required this.id,
    required this.name,
    required this.description,
    required this.size,
    required this.color,
    required this.icon,
    required this.recommended,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: selected ? DroidTheme.surfaceLight : DroidTheme.cardBg,
          borderRadius: BorderRadius.circular(DroidTheme.radiusLg),
          border: Border.all(
            color: selected ? color.withValues(alpha: 0.6) : DroidTheme.surfaceBorder,
            width: selected ? 1.5 : 1,
          ),
          boxShadow: selected
              ? [
                  BoxShadow(
                    color: color.withValues(alpha: 0.15),
                    blurRadius: 20,
                    spreadRadius: 0,
                  ),
                ]
              : [],
        ),
        child: Row(
          children: [
            // ── Distro Icon ──
            Container(
              width: 52,
              height: 52,
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(14),
              ),
              child: Icon(icon, color: color, size: 26),
            ),
            const SizedBox(width: 16),

            // ── Text Content ──
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text(name, style: DroidTheme.headingSm),
                      if (recommended) ...[
                        const SizedBox(width: 8),
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 8,
                            vertical: 2,
                          ),
                          decoration: BoxDecoration(
                            color: DroidTheme.accent.withValues(alpha: 0.15),
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(
                            l10n.recommendedBadge,
                            style: DroidTheme.label.copyWith(
                              color: DroidTheme.accent,
                              fontSize: 9,
                            ),
                          ),
                        ),
                      ],
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(description, style: DroidTheme.bodySm),
                  const SizedBox(height: 6),
                  Text(
                    size,
                    style: DroidTheme.monoSm.copyWith(color: DroidTheme.textDim),
                  ),
                ],
              ),
            ),

            // ── Selection Indicator ──
            AnimatedContainer(
              duration: const Duration(milliseconds: 200),
              width: 24,
              height: 24,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: selected ? color : Colors.transparent,
                border: Border.all(
                  color: selected ? color : DroidTheme.textDim,
                  width: 2,
                ),
              ),
              child: selected
                  ? const Icon(Icons.check, size: 14, color: Colors.white)
                  : null,
            ),
          ],
        ),
      ),
    );
  }
}
