import 'app_strings.dart';

/// Spanish (default language).
class AppStringsEs extends AppStrings {
  const AppStringsEs();

  // ── Common ──
  @override String get back => 'Atrás';
  @override String get next => 'Siguiente';
  @override String get cancel => 'Cancelar';
  @override String get retry => 'Reintentar';
  @override String get goBack => 'Volver';
  @override String get install => 'Instalar';
  @override String get delete => 'Eliminar';
  @override String get restore => 'Restaurar';
  @override String get switchToLightTheme => 'Cambiar a tema claro';
  @override String get switchToDarkTheme => 'Cambiar a tema oscuro';

  // ── Welcome ──
  @override String get welcomeTagline => 'Escritorio Linux completo en Android';
  @override String get welcomeHighlights => 'Ubuntu · Escritorio XFCE · Una sola app';
  @override String get featureContainerized => 'En contenedor';
  @override String get featureRootOptional => 'Root opcional';
  @override String get featureLinuxDesktop => 'Escritorio Linux';
  @override String get featureLocalExecution => 'Ejecución local';
  @override String get setUpDesktopEssentials => 'Configurar componentes esenciales';

  // ── Distro picker ──
  @override String get chooseLinux => 'Elegir Linux';
  @override String get chooseLinuxSubtitle =>
      'Selecciona una distribución para instalar. Se descargará durante la configuración.';
  @override String get recommendedBadge => 'RECOMENDADO';
  @override
  String distroDescription(String id) => switch (id) {
        'ubuntu' => 'La mejor experiencia general. Enorme catálogo de paquetes y gran comunidad.',
        'alpine' => 'Ultraligero y seguro. Ideal para usar pocos recursos.',
        'kali' => 'Herramientas de seguridad y pentesting. Incluye Wireshark, Metasploit y Nmap.',
        _ => '',
      };
  @override String downloadSize(String size) => '$size de descarga';

  // ── Desktop picker ──
  @override String get chooseDesktop => 'Elegir escritorio';
  @override String get chooseDesktopSubtitle =>
      'Los componentes esenciales instalan el escritorio elegido, una terminal, un administrador '
      'de archivos y herramientas básicas. Podrás añadir más aplicaciones después.';
  @override String get bestBadge => 'MEJOR';
  @override String get installEssentials => 'Instalar componentes esenciales';
  @override
  String desktopDescription(String id) => switch (id) {
        'xfce4' => 'Rápido, personalizable y con bajo consumo. La opción más equilibrada.',
        'lxqt' => 'Escritorio ultraligero basado en Qt. La opción más rápida.',
        'mate' => 'Derivado clásico de GNOME 2. Familiar y cómodo.',
        'kde' => 'Moderno y completo. Necesita una GPU potente y 4 GB de RAM o más.',
        _ => '',
      };

  // ── Setup progress ──
  @override String get lowStorageTitle => 'Poco almacenamiento';
  @override
  String lowStorageMessage(int freeMb) =>
      'Solo hay $freeMb MB disponibles. Los componentes esenciales funcionan mejor con al menos '
      '2 GB libres. Puedes continuar, pero la instalación de paquetes podría fallar.';
  @override String get continueAnyway => 'Continuar de todos modos';
  @override String get rootDetectedTitle => 'Acceso root detectado';
  @override String get rootDetectedMessage =>
      'Tu dispositivo tiene root. ¿Continuar con el entorno chroot con root para obtener el '
      'mejor rendimiento y compatibilidad completa con Linux?';
  @override String get continueWithRoot => 'Continuar con root';
  @override String get launchDroidDesk => 'Abrir DroidDesk';
  @override String get stepBootstrap => 'Preparar el entorno base';
  @override String get stepConfigureRepositories => 'Configurar repositorios de paquetes';
  @override String get stepInstallEssentials => 'Instalar componentes esenciales';
  @override String get stepFinalizeEssentials => 'Finalizar componentes esenciales';
  @override String get stepRootConfirmed => 'Acceso root confirmado';
  @override String get stepDownloadUbuntuRootfs => 'Descargar rootfs de Ubuntu';
  @override String get stepInstallNativePackages => 'Instalar paquetes nativos';
  @override String get stepExtractRootfs => 'Extraer rootfs';
  @override String get stepConfigureDesktop => 'Configurar escritorio';
  @override String get stepConfigureLinux => 'Configurar Linux';
  @override String get phaseSetupFailed => 'La configuración falló';
  @override String get phaseDownloading => 'Descargando';
  @override String get preparingDownload => 'Preparando descarga...';
  @override String get phasePreparingRuntime => 'Preparando entorno';
  @override String get phaseInstallingNativeLinux => 'Instalando Linux nativo';
  @override String get preparingNativeTermux => 'Preparando el entorno nativo de Termux...';
  @override String get phaseInstallingDesktop => 'Instalando escritorio';
  @override String get phaseExtracting => 'Extrayendo';
  @override String get extractingFilesystem => 'Extrayendo sistema de archivos...';
  @override String get phaseSetupComplete => '¡Configuración completa!';
  @override String get setupCompleteMessage => 'Tu escritorio Linux está listo para iniciarse.';
  @override String get phaseSettingUp => 'Configurando';
  @override String get initializing => 'Iniciando...';

  // ── Desktop install screen ──
  @override String get installationComplete => 'Instalación completa';
  @override String get installationFailedTitle => 'La instalación falló';
  @override String get configuringWorkstation => 'Configurando\nestación de trabajo Linux';
  @override String get linuxEnvironmentReady => 'Tu entorno Linux está listo.';
  @override String get downloadingSystemPackages => 'Descargando y configurando paquetes del sistema.';
  @override String get extractingPackages => 'Extrayendo paquetes...';
  @override String get returnToHome => 'Volver al inicio';
  @override String get supportOpenSource => 'Apoya el código abierto';

  // ── Home ──
  @override String get desktopRunning => 'Escritorio en ejecución';
  @override String get ready => 'Listo';
  @override String get quickActions => 'ACCIONES RÁPIDAS';
  @override String installDesktop(String desktop) => 'Instalar $desktop';
  @override String get installDesktopSubtitle =>
      'Instalar los paquetes del entorno de escritorio (solo una vez)';
  @override String get returnToDesktop => 'Volver al escritorio';
  @override String runningInBackground(String desktop) => '$desktop se está ejecutando en segundo plano';
  @override String get stopServer => 'Detener servidor';
  @override String get launchDesktop => 'Iniciar escritorio';
  @override String get shutdownLinux => 'Apagar el entorno Linux';
  @override String startDesktopEnvironment(String desktop) => 'Iniciar el entorno de escritorio $desktop';
  @override String get noDesktopInstalled =>
      'No hay ningún entorno de escritorio instalado. Completa primero la configuración.';
  @override String get terminal => 'Terminal';
  @override
  String terminalSubtitle({required bool chroot}) => chroot
      ? 'Abrir una shell Linux en el entorno chroot de Ubuntu'
      : 'Abrir una shell Linux en el entorno nativo de Termux';
  @override String get debianShell => 'Shell de Debian';
  @override String get debianShellSubtitle =>
      'Abrir el entorno opcional mínimo de compatibilidad PRoot';
  @override String get addApplications => 'Añadir aplicaciones';
  @override String get addApplicationsSubtitle =>
      'Instalar aplicaciones o la compatibilidad opcional con Debian';
  @override String get systemSection => 'SISTEMA';
  @override String get infoDistribution => 'Distribución';
  @override String get infoDesktop => 'Escritorio';
  @override String get infoRenderer => 'Renderizador';
  @override String get infoDevice => 'Dispositivo';
  @override String get infoStorageFree => 'Almacenamiento libre';
  @override String get automatic => 'Automático';
  @override String get notAvailable => 'N/D';
  @override String get desktopActive => 'Escritorio activo';
  @override String get desktopIdle => 'Escritorio inactivo';
  @override String get tapLaunchDesktop => 'Toca «Iniciar escritorio» para empezar';
  @override String get termuxNative => 'Termux nativo';

  // ── Local AI ──
  @override String get localAi => 'IA local';
  @override String get localAiActive => 'IA activa';
  @override String get localAiStopped => 'IA detenida';
  @override String get localAiStarting => 'IA iniciando...';
  @override String get localAiApiReady => 'API local lista';
  @override String get localAiApiUnavailable => 'API local no disponible';
  @override String get localAiControllerMissing =>
      'El controlador de IA aún no está instalado';
  @override String get startLocalAi => 'Iniciar IA';
  @override String get stopLocalAi => 'Detener IA';
  @override String get restartLocalAi => 'Reiniciar IA';
  @override String get testLocalAi => 'Probar IA';
  @override String get openLocalAiChat => 'Abrir chat';
  @override String get localAiTestOk => 'La IA respondió correctamente';
  @override String localAiModel(String model) => 'Modelo: $model';
  @override String localAiActionFailed(String error) =>
      'La acción de IA falló: $error';

  // ── Settings sheet ──
  @override String get settings => 'Ajustes';
  @override String get appTheme => 'Tema de la app';
  @override String get systemDefaultTheme => 'Predeterminado del sistema';
  @override String get darkTheme => 'Tema oscuro';
  @override String get lightTheme => 'Tema claro';
  @override String get themeDark => 'Oscuro';
  @override String get themeLight => 'Claro';
  @override String get themeSystem => 'Sistema';
  @override String get batteryOptimization => 'Optimización de batería';
  @override String get batteryOptimizationSubtitle => 'Desactívala para evitar que se cierre la sesión';
  @override String get setDefaultLauncher => 'Usar como app de inicio predeterminada';
  @override String get setDefaultLauncherSubtitle => 'Abrir el escritorio Linux al encender el teléfono';
  @override String get stopDefaultLauncher => 'Dejar de usar como app de inicio';
  @override String get stopDefaultLauncherSubtitle => 'Elegir directamente otra app de inicio';
  @override String get desktopTools => 'Herramientas del escritorio';
  @override String get desktopToolsSubtitle => 'Gestionar las apps del dock y las copias del escritorio';
  @override String get emergencyExit => 'Salida de emergencia';
  @override String get androidSettings => 'Ajustes de Android';
  @override String get androidSettingsSubtitle => 'Abrir directamente los ajustes del sistema Android';
  @override String get changeHomeApp => 'Cambiar app de inicio';
  @override String get changeHomeAppSubtitle => 'Abrir el selector de app de inicio de Android';
  @override String get returnToSamsungHome => 'Volver a Samsung Home';
  @override String get returnToSamsungHomeSubtitle => 'Abrir One UI Home directamente';
  @override String get reinstallLinux => 'Reinstalar Linux';
  @override String get reinstallLinuxSubtitle => 'Volver a descargar y configurar el rootfs';
  @override String get reinstallLinuxTitle => '¿Reinstalar el entorno Linux?';
  @override String get reinstallLinuxMessage =>
      'Se eliminarán los paquetes Linux y el entorno de escritorio instalados, y se volverán a '
      'instalar desde cero.\n\nLos archivos de tu carpeta personal se conservarán. Esta acción no '
      'se puede deshacer.';
  @override String get reinstall => 'Reinstalar';
  @override String get changeLauncherTitle => '¿Cambiar la app de inicio predeterminada?';
  @override String get changeLauncherMessage =>
      'DroidDesk dejará de abrirse automáticamente como app de inicio. Android te pedirá que '
      'elijas otra.';
  @override String get changeLauncher => 'Cambiar app de inicio';

  // ── Terminal ──
  @override String get terminalWelcome => 'Terminal Linux de DroidDesk\nEscribe los comandos abajo.\n';
  @override String get commandInterrupted => '\n^C (Comando interrumpido)\n';
  @override String get containerClosed =>
      'El shell de Debian (PRoot) no está abierto.\n';
  @override String containerExited(int? exitCode) => exitCode == null
      ? '\n[PRoot no pudo iniciarse; el entorno nativo no se ha tocado]\n'
      : '\n[Shell de Debian (PRoot) cerrado, código $exitCode]\n';
  @override String get interruptCommand => 'Interrumpir comando (Ctrl+C)';
  @override String get enterCommand => 'Escribe un comando...';
  @override String get terminalModeCompat => 'compat';
  @override String get terminalModeNative => 'nativo';

  // ── App catalog ──
  @override
  String catalogAppDescription(String id) => switch (id) {
        'firefox' => 'Navegador web de escritorio rápido y privado.',
        'code-oss' => 'Potente editor de código e IDE de escritorio.',
        'libreoffice' => 'Documentos, hojas de cálculo y presentaciones.',
        'gimp' => 'Edición de imágenes y herramientas de diseño profesionales.',
        'blender' => 'Suite completa de creación 3D de código abierto.',
        'vlc' => 'Reproduce casi cualquier formato de audio y vídeo.',
        'nodejs' => 'Entorno de ejecución de JavaScript y gestor de paquetes.',
        'python' => 'Lenguaje de programación popular y sus herramientas.',
        'imagemagick' => 'Herramientas de conversión y procesamiento de imágenes.',
        _ => '',
      };
  @override String preparingPackage(String package) => 'Preparando $package...';
  @override String packageInstallCancelled(String package) => 'Instalación de $package cancelada';
  @override String packageInstalled(String package) => '$package instalado';
  @override String packageInstallFailed(String package) => 'La instalación de $package falló';
  @override String get cancellingInstallation => 'Cancelando instalación...';
  @override String removePackageTitle(String package) => '¿Desinstalar $package?';
  @override String get removePackageMessage => 'Los paquetes dependientes también podrían verse afectados.';
  @override String get uninstall => 'Desinstalar';
  @override String get preparingRemoval => 'Preparando desinstalación...';
  @override String packageRemoved(String package) => '$package desinstalado';
  @override String get removalFailed => 'La desinstalación falló';
  @override String get linuxAppStore => 'Tienda de apps Linux';
  @override String get tabFeatured => 'Destacadas';
  @override String get tabBrowse => 'Explorar';
  @override String get tabInstalled => 'Instaladas';
  @override String get debianCompatibility => 'Compatibilidad con Debian';
  @override String get debianCompatibilityDescription =>
      'Ejecuta paquetes que no están en los repositorios nativos.';
  @override String get popularLinuxApps => 'Aplicaciones Linux populares';
  @override String get handPickedApps => 'Apps seleccionadas y probadas para DroidDesk.';
  @override String get searchPackages => 'Buscar paquetes, apps y herramientas';
  @override String get noMatchingPackages => 'No se encontraron paquetes';
  @override String get noPackagesInstalled => 'Aún no hay paquetes instalados';
  @override String get linuxPackage => 'Paquete Linux';
  @override String get guiApp => 'App gráfica';
  @override String get sectionSystem => 'Sistema';
  @override String get cancelling => 'Cancelando';
  @override String get cancelInstallation => 'Cancelar instalación';

  // ── Desktop tools ──
  @override String get dockUpdated => 'Dock actualizado';
  @override String get dockUpdateFailed => 'No se pudo actualizar el dock';
  @override String get snapshotCreated => 'Copia del escritorio creada';
  @override String snapshotFailed(String error) => 'No se pudo crear la copia: $error';
  @override String get restoreSnapshotTitle => '¿Restaurar esta copia?';
  @override String get restoreSnapshotMessage =>
      'La sesión Linux se detendrá. Los archivos y ajustes actuales de la carpeta personal se '
      'reemplazarán y se reinstalarán los paquetes que falten.';
  @override String get snapshotRestored => 'Copia restaurada';
  @override String get restoreFailed => 'La restauración falló';
  @override String restoreFailedWithError(String error) => 'La restauración falló: $error';
  @override String get deleteSnapshotTitle => '¿Eliminar la copia?';
  @override String get tabDock => 'Dock';
  @override String get tabBackups => 'Copias de seguridad';
  @override String get pinnedSection => 'FIJADAS';
  @override String get addAndroidAppSection => 'AÑADIR UNA APP DE ANDROID';
  @override String get searchInstalledApps => 'Buscar apps instaladas';
  @override String get createSnapshot => 'Crear copia del escritorio';
  @override String get createSnapshotDescription =>
      'Guarda tu carpeta personal de Linux, los ajustes del escritorio, el fondo de pantalla y la '
      'lista de paquetes instalados.';
  @override String get snapshotsSection => 'COPIAS';
  @override String get noSnapshots => 'Aún no hay copias';

  // ── App state (status and errors) ──
  @override String get unknownGpu => 'GPU desconocida';
  @override String runtimeStatusFailed(String error) => 'No se pudo obtener el estado del entorno: $error';
  @override String setupFailed(String error) => 'La configuración falló: $error';
  @override String get extractingTermuxBootstrap => 'Extrayendo el entorno base nativo de Termux...';
  @override String get bootstrapReady => 'Entorno base listo';
  @override String get nativeInstallFailed => 'Falló la instalación de los paquetes nativos de Termux';
  @override String get downloadingUbuntuRootfs => 'Descargando rootfs de Ubuntu...';
  @override String get ubuntuDownloadFailed =>
      'La descarga de Ubuntu falló. Revisa tu conexión y vuelve a intentarlo.';
  @override String get extractingRootfs => 'Extrayendo rootfs...';
  @override String get ubuntuExtractionFailed => 'Falló la extracción del sistema de archivos de Ubuntu';
  @override String get installingDesktopEnvironmentLong =>
      'Instalando el entorno de escritorio (puede tardar un rato)...';
  @override String get essentialsInstallFailed => 'Falló la instalación de los componentes esenciales';
  @override String get extractionHandledBySetup => 'El asistente de configuración se encarga de la extracción';
  @override String get installingDesktopEnvironment => 'Instalando el entorno de escritorio...';
  @override String installationFailed(String error) => 'La instalación falló: $error';
  @override String get preparingInstallation => 'Preparando la instalación...';
  @override String get runtimeNotReady => 'El entorno Linux no está listo';
  @override String startFailed(String error) => 'No se pudo iniciar: $error';
  @override String launchDesktopFailed(String error) => 'No se pudo abrir el escritorio: $error';
  @override String stopFailed(String error) => 'No se pudo detener: $error';
  @override String reinstallFailed(String error) => 'La reinstalación falló: $error';
}
