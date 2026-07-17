# K8s 部署

> 開發/測試用清單。正式環境建議：基礎設施改用託管服務或 Operator、加上 PVC 持久化、資源 requests/limits、HPA、Ingress、密鑰用外部 Secret 管理。

## 步驟
```bash
# 1. 建置各服務映像（先在 backend/ 執行 mvn clean install -DskipTests）
cd backend && mvn clean install -DskipTests && cd ..
./k8s/build-images.sh          # 產生 quant/<service>:1.0.0-SNAPSHOT
# 若用 minikube：eval $(minikube docker-env) 後再 build，映像才在叢集內

# 2. 套用清單
kubectl apply -f k8s/00-namespace.yaml
kubectl apply -f k8s/01-configmap.yaml     # 先把 Secret 內的幣安金鑰填好（或用 kubectl create secret）
kubectl apply -f k8s/02-infra.yaml
kubectl -n quant rollout status deploy/nacos    # 等基礎設施就緒
kubectl apply -f k8s/03-services.yaml

# 3. 存取
kubectl -n quant get pods
# gateway 對外：NodePort 30080（minikube: minikube service api-gateway-external -n quant）
```

## 檔案
- `00-namespace.yaml`  namespace `quant`
- `01-configmap.yaml`  環境設定 + 幣安金鑰 Secret
- `02-infra.yaml`      TimescaleDB / Redis / Kafka(KRaft) / Nacos
- `03-services.yaml`   8 個微服務 Deployment/Service + gateway NodePort
- `build-images.sh`    建置映像
- `../backend/Dockerfile` 通用執行映像

## 注意
- 合約/現貨金鑰請用 Secret，勿寫進 ConfigMap。
- 基礎設施此處無持久化（重啟即清空），正式請掛 PVC。
- 資料庫初始化 SQL 未自動套用；可用 initContainer 或 Job 執行 infra/init/timescale-init.sql，或倚賴各服務 JPA ddl-auto。
