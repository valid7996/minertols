# Whatsminer Monitor (Android / Kotlin)

اپلیکیشن اندروید برای مانیتور کردن دستگاه‌های ماینر **Whatsminer** روی شبکه محلی (وای‌فای).
برنامه به صورت خودکار کل ساب‌نت وای‌فای گوشی را اسکن می‌کند، ماینرهایی که پورت API آن‌ها
(4028) باز است را پیدا می‌کند، و برای هرکدام موارد زیر را نمایش می‌دهد:

- آدرس IP دستگاه
- مدت زمان روشن بودن (uptime)
- دمای میانگین و دمای هر هش‌بورد
- هشریت کل و هشریت هر هش‌بورد
- دور فن ورودی و خروجی
- مصرف برق تقریبی
- نسخه فریمور

## ساختار پروژه

```
WhatsminerMonitor/
├── app/
│   ├── src/main/java/com/miner/whatsminermonitor/
│   │   ├── MainActivity.kt          # رابط کاربری Jetpack Compose
│   │   ├── model/MinerInfo.kt       # مدل‌های داده (MinerInfo, HashboardInfo)
│   │   ├── network/
│   │   │   ├── WhatsminerClient.kt  # کلاینت پروتکل JSON-over-TCP روی پورت 4028
│   │   │   └── NetworkScanner.kt    # پیدا کردن ساب‌نت وای‌فای و اسکن موازی
│   │   └── ui/
│   │       ├── MinerViewModel.kt    # مدیریت وضعیت اسکن و لیست ماینرها
│   │       └── theme/Theme.kt
│   └── src/main/AndroidManifest.xml
├── build.gradle.kts
└── settings.gradle.kts
```

## نحوه بیلد گرفتن

1. پوشه پروژه را در **Android Studio** (Hedgehog یا جدیدتر) باز کنید — Open an existing project.
2. صبر کنید Gradle Sync تمام شود (اولین بار دانلود دیپندنسی‌ها زمان می‌برد).
3. گوشی/شبیه‌ساز را انتخاب کرده و Run بزنید، یا از منوی `Build > Generate Signed Bundle / APK` برای گرفتن APK استفاده کنید.

> این پروژه در محیط sandbox قابل کامپایل نبود (به SDK اندروید و ریپازیتوری Google Maven
> دسترسی نداشت)، بنابراین کد به‌صورت دستی و با دقت طبق مستندات رسمی نوشته شده اما پیشنهاد
> می‌شود بعد از Sync اول، یک بیلد امتحانی بگیرید تا مطمئن شوید نسخه‌های Gradle/AGP با
> Android Studio نصب‌شده شما سازگار است (در صورت نیاز نسخه AGP در `build.gradle.kts` را
> با نسخه پیشنهادی خود Android Studio هماهنگ کنید).

## نکته مهم درباره API ماینر Whatsminer

- پورت پیش‌فرض API روی همه ماینرهای Whatsminer برابر **4028** است و پروتکل آن JSON روی TCP
  (مشابه cgminer API) است.
- دستورات خواندنی مثل `summary` و `devs` به صورت پیش‌فرض بدون رمز فعال هستند و برنامه از همین
  دستورات ساده (متنی، بدون رمزنگاری) استفاده می‌کند.
- **در فریمورهای جدیدتر Whatsminer**، API ممکن است فقط به صورت رمزنگاری‌شده (نیازمند
  یوزر/پسورد و تبادل توکن AES) پاسخ بدهد. اگر برنامه برای یک IP خاص «پاسخی دریافت نشد»
  نشان داد ولی مطمئن هستید ماینر روشن است، یعنی احتمالاً باید حالت رمزنگاری‌شده API را
  از طریق نرم‌افزار WhatsminerTool غیرفعال کنید (Remote Ctrl > Miner API Switch)، یا کد را
  طبق مستندات رسمی [Whatsminer API](https://www.whatsminer.com) برای پشتیبانی از حالت
  رمزنگاری‌شده گسترش دهید.
- چون نام دقیق فیلدهای JSON بین نسخه‌های مختلف فریمور کمی فرق دارد، `WhatsminerClient.kt`
  چند نام محتمل برای هر مقدار (دما، فن، هشریت و ...) امتحان می‌کند. اگر مقداری برای دستگاه
  شما "—" نمایش داده شد، بهترین کار این است که با یک ابزار مثل `nc` یا `telnet` دستور
  `{"command":"summary"}` و `{"command":"devs"}` را مستقیم به پورت 4028 دستگاه بفرستید،
  خروجی JSON واقعی را ببینید، و نام کلید مربوطه را به لیست‌های `findDouble/findInt/...` در
  همان فایل اضافه کنید.

## مجوزهای مورد نیاز

- `INTERNET` و `ACCESS_NETWORK_STATE` برای اتصال TCP به ماینرها روی شبکه محلی.
- `ACCESS_WIFI_STATE` برای تشخیص ساب‌نت وای‌فای.

توجه: چون فقط از سوکت خام TCP استفاده شده (نه HTTP)، نیازی به مجوز موقعیت مکانی (Location)
برای اسکن شبکه نیست.

## آپلود در گیت‌هاب

```bash
cd WhatsminerMonitor
git init
git add .
git commit -m "Initial commit: Whatsminer network monitor (Android/Kotlin)"
git branch -M main
git remote add origin https://github.com/<username>/<repo-name>.git
git push -u origin main
```
