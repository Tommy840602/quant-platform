# 前端 (Vue 3)

```bash
npm install
npm run dev      # http://localhost:5173（透過 Vite proxy 走 API Gateway :8080）
```

頁面：儀表板、策略管理、回測結果、實盤監控、帳戶部位、訂單紀錄、風控設定。
狀態管理 Pinia（market / account store），即時行情以 WebSocket 直連 testnet（正式改走 gateway /ws）。
標註 `TODO` 處為待串接後端 API 的骨架點。
