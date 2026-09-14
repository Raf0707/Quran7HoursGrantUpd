Quran7Hours Android — bundled offline QPC pack
===============================================

Runtime MUST NOT download or extract Mushaf assets.
Gradle MUST NOT download them either.

A fresh developer checkout is prepared ONCE with:

  powershell -ExecutionPolicy Bypass -File tools/install_quran_offline_pack.ps1

The one-time installer downloads three archives (not 1812 individual requests)
and places the final files directly into Android assets:

  layout/page-001.json ... page-604.json
  fonts/v4/p1.ttf ... p604.ttf   (QPC/QCF V4 Tajweed)
  fonts/v2/p1.ttf ... p604.ttf   (QPC/QCF V2 plain)

After those files exist, Android Studio Sync / Run / assembleDebug performs no
Mushaf network work and does not require Python. The APK/AAB itself contains the
complete pack, so the installed app opens Mushaf pages and toggles Tajweed fully
offline.

Optional local verification:

  powershell -ExecutionPolicy Bypass -File tools/verify_quran_offline_pack.ps1
