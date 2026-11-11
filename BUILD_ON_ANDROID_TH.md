# วิธีสร้าง APK โดยใช้มือถือ Android อย่างเดียว

โปรเจกต์นี้เตรียม GitHub Actions ไว้ให้สร้าง APK บนคลาวด์ ไม่ต้องติดตั้ง Android Studio บนมือถือ

1. เปิด https://github.com แล้วสมัคร/เข้าสู่ระบบ
2. กด New repository ตั้งชื่อ `Nattawut-SmartGauge` เลือก Private หรือ Public แล้ว Create repository
3. แตกไฟล์ ZIP นี้ในมือถือด้วยแอปจัดการไฟล์ แล้วอัปโหลดไฟล์และโฟลเดอร์ทั้งหมดในโปรเจกต์เข้า repository (ต้องรวมโฟลเดอร์ `.github/workflows` ด้วย)
4. เปิดแท็บ Actions ของ repository แล้วเลือก `Build Nattawut SmartGauge APK`
5. กด Run workflow และรอจนสถานะเป็นสีเขียว (Success)
6. เปิดงานที่สำเร็จ เลื่อนลงหัวข้อ Artifacts แล้วดาวน์โหลด `Nattawut-SmartGauge-debug-apk`
7. แตก ZIP ที่ดาวน์โหลด จะได้ `app-debug.apk` แตะติดตั้ง และอนุญาตให้ Chrome/Files ติดตั้งแอปจากแหล่งนี้เมื่อ Android ถาม

# วิธีทดสอบ Bluetooth

1. จ่ายไฟให้ ESP32 ด้วยแหล่งจ่ายที่เหมาะสม และอัปโหลดไฟล์ `ทดสอบเชื่อมบลูทูธ1.ino` ที่แนบมากับงานนี้
2. เปิด Bluetooth มือถือ ไปที่ Settings > Bluetooth > Pair new device แล้วมองหา `Nattawut ECU`
3. เลือกจับคู่ หากถาม PIN ให้ลอง `1234` หรือ `0000` เฉพาะเมื่ออุปกรณ์ร้องขอ
4. เปิดแอป Nattawut SmartGauge กด CONNECT และเลือก `Nattawut ECU`
5. เปิดหน้า Dashboard ค่าที่ ECU ยังไม่ส่ง/ยังไม่ยืนยันจะแสดง `--` ตามจริง

# ข้อควรทราบ

- ต้องใช้บอร์ด ESP32 รุ่นที่รองรับ Bluetooth Classic SPP (เช่น ESP32 รุ่นดั้งเดิม/CYD ที่ใช้ ESP32-WROOM) ไม่รองรับ ESP32-S2/S3/C3 ผ่าน Bluetooth Classic
- Firmware ส่งข้อมูลทางเดียวด้วย CSV v1 ทุก 250 ms; DTC read/clear, MAP sync และ remote control ยังถูกล็อกไว้ เพราะยังไม่มี API Bluetooth ที่ยืนยันแล้ว
- อย่าทดสอบการเชื่อมต่อขณะขับขี่ และอย่าสั่งลบ DTC/เขียน ECU จากคำสั่งที่ยังไม่ผ่านการทดสอบ
