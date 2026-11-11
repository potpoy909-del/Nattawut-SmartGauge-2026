# Nattawut SmartGauge Android V2 — Stage 2.1

แอป Android Kotlin สำหรับ Bluetooth Classic SPP (RFCOMM) และ firmware ESP32 ที่ส่ง CSV v1

## สร้าง APK ด้วยมือถือ
ดู `BUILD_ON_ANDROID_TH.md` — ใช้ GitHub Actions สร้าง APK บนคลาวด์ ไม่ต้องใช้คอมพิวเตอร์

## โปรโตคอล
`NATTWUT,1,RPM,TPS,ECT,IAT,BAT,O2,AFR,SPEED,INJ,IGN,MAP,STFT,LTFT`

ส่งทุก 250 ms เมื่อมี client เชื่อมต่อ `NA` คือไม่มีข้อมูลที่ยืนยันแล้ว แอปจะแสดง `--` โดยไม่สร้างค่าจำลอง

## หน้าที่มี
- Dashboard: 13 ช่อง telemetry
- Graph: เลือก RPM/TPS/ECT/BAT/SPEED และแสดงประวัติที่ได้รับจริง
- Logger: บันทึก packet ที่รับจริงและส่งออก CSV
- DTC/MAP/Control: ปิดไว้จนกว่าจะมี API ฝั่ง ESP32 ที่ผ่านการยืนยัน

## ข้อจำกัด
ต้องเป็น ESP32 ที่รองรับ Bluetooth Classic SPP (ESP32 ดั้งเดิม/CYD บางรุ่น) ไม่รองรับ ESP32-S2/S3/C3 ผ่าน SPP แบบนี้ แอปและ firmware ต้องทดสอบกับบอร์ดจริงก่อนใช้งานบนรถ

## Firmware ที่แนบ
ไฟล์ `firmware/Nattawut_SmartGauge_Bluetooth_Test.ino` คือไฟล์ Arduino ที่ผู้ใช้ส่งมาและใช้ชื่อ Bluetooth `Nattawut ECU` โปรดตรวจสอบรุ่นบอร์ด/ขา ECU ให้ตรงกับการต่อสายจริงก่อนอัปโหลด

## REMAP ROM Manager (เริ่มต้น)
- เพิ่มหน้า REMAP ในแอป Android
- เลือกไฟล์ BIN จาก Android Storage Access Framework
- แสดงชื่อไฟล์ ขนาดไฟล์ และ SHA-256
- โปรไฟล์เริ่มต้นจากตารางที่ผู้ใช้ส่ง: Honda Wave 110i, ECU 38770-K58-T81, ROM 64 KB, Start Address 0x8000, Checksum Address 0x0000
- ส่งออกรายงานการตรวจสอบไฟล์ ROM ได้
- ปุ่มเขียน ECU ยังคงล็อกไว้จนกว่าจะยืนยัน Bootloader/Security Access/Erase/Program/Checksum/Verify สำหรับ ECU รุ่นนี้
- ยังไม่แสดงจำนวนครั้งรีแมพย้อนหลังเป็นตัวเลข เพราะยังไม่มีข้อมูลนับจาก ECU หรือประวัติที่ตรวจสอบได้
