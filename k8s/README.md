# Kubernetes Deployment for Recurra

This directory contains Kubernetes manifests for deploying Recurra to a Kubernetes cluster.

## Prerequisites

- Kubernetes cluster (1.25+)
- kubectl configured
- Docker image built and available: `recurra-proxy:latest`
- Persistent volume provisioner (for storage)
- Nginx Ingress Controller (optional, for Ingress)

## Quick Start

### 1. Build Docker Image

```bash
# Build the application
./mvnw clean package -DskipTests

# Build Docker image
docker build -t recurra-proxy:latest .

# Tag and push to your registry (if using remote cluster)
docker tag recurra-proxy:latest your-registry/recurra-proxy:latest
docker push your-registry/recurra-proxy:latest
```

### 2. Configure Secrets

Edit `k8s/secret.yaml` and add your provider API keys:

```yaml
stringData:
  openai-api-key: "sk-..."
  anthropic-api-key: "sk-ant-..."
  bedrock-credentials: "AWS_ACCESS_KEY:AWS_SECRET_KEY"
```

### 3. Deploy to Kubernetes

```bash
# Create namespace and resources
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/secret.yaml

# Deploy PostgreSQL and Redis
kubectl apply -f k8s/postgres.yaml
kubectl apply -f k8s/redis.yaml

# Wait for databases to be ready
kubectl wait --for=condition=ready pod -l app=postgres -n recurra --timeout=300s
kubectl wait --for=condition=ready pod -l app=redis -n recurra --timeout=300s

# Deploy application
kubectl apply -f k8s/deployment.yaml

# Apply autoscaling
kubectl apply -f k8s/hpa.yaml

# Apply ingress (optional)
kubectl apply -f k8s/ingress.yaml
```

### 4. Verify Deployment

```bash
# Check pod status
kubectl get pods -n recurra

# Check services
kubectl get svc -n recurra

# View logs
kubectl logs -f deployment/recurra -n recurra

# Check health
kubectl port-forward svc/recurra 8080:80 -n recurra
curl http://localhost:8080/health
```

## Architecture

```
┌─────────────┐
│   Ingress   │
└──────┬──────┘
       │
┌──────▼──────────────┐
│  Recurra Service    │  (3-10 replicas, auto-scaled)
│  (Spring Boot App)  │
└──────┬──────────────┘
       │
       ├─────────────┐
       │             │
┌──────▼──────┐  ┌──▼───────┐
│   Redis     │  │ Postgres │
│  (Cache)    │  │ (Storage)│
└─────────────┘  └──────────┘
```

## Configuration

### Environment Variables

All sensitive configuration is managed via Kubernetes Secrets:

- `postgres-password`: PostgreSQL password
- `openai-api-key`: OpenAI API key
- `anthropic-api-key`: Anthropic API key
- `bedrock-credentials`: AWS credentials (format: `ACCESS_KEY:SECRET_KEY`)
- `bedrock-region`: AWS region for Bedrock

### ConfigMap

Application configuration is in `k8s/configmap.yaml`:

- Cache settings (similarity threshold, TTL)
- Database connection pooling
- Redis configuration
- Logging levels

### Resource Limits

**Recurra App**:
- Requests: 1 CPU, 2Gi RAM
- Limits: 2 CPU, 4Gi RAM

**PostgreSQL**:
- Requests: 500m CPU, 1Gi RAM
- Limits: 1 CPU, 2Gi RAM

**Redis**:
- Requests: 250m CPU, 512Mi RAM
- Limits: 500m CPU, 2Gi RAM

## Autoscaling

HPA scales based on:
- CPU utilization: 70% target
- Memory utilization: 80% target
- Min replicas: 3
- Max replicas: 10

Scale-up policy: Fast (double capacity in 30s)
Scale-down policy: Conservative (50% reduction per minute)

## Storage

### PostgreSQL
- PVC: 20Gi
- StorageClass: `standard`
- Contains cache entries, embeddings, and metadata

### Redis
- PVC: 10Gi
- StorageClass: `standard`
- Persistence enabled with RDB snapshots

## Monitoring

### Health Checks

- **Liveness**: `/health` endpoint, checks app responsiveness
- **Readiness**: `/health` endpoint, checks if app can serve traffic
- **Startup**: `/health` endpoint, allows 5 minutes for startup

### Prometheus Metrics

Metrics exposed at `/actuator/prometheus`:

```bash
kubectl port-forward svc/recurra 8080:80 -n recurra
curl http://localhost:8080/actuator/prometheus
```

## Upgrading

### Rolling Update

```bash
# Update image
kubectl set image deployment/recurra recurra=recurra-proxy:v2 -n recurra

# Watch rollout
kubectl rollout status deployment/recurra -n recurra

# Rollback if needed
kubectl rollout undo deployment/recurra -n recurra
```

### Database Migrations

Flyway migrations run automatically on startup. For zero-downtime deployments:

1. Ensure migrations are backward compatible
2. Deploy new version with `replicas: 1` first
3. Wait for migration to complete
4. Scale up to desired replicas

## Troubleshooting

### Pods Not Starting

```bash
# Check events
kubectl describe pod <pod-name> -n recurra

# Check logs
kubectl logs <pod-name> -n recurra

# Check init containers
kubectl logs <pod-name> -c wait-for-postgres -n recurra
```

### Database Connection Issues

```bash
# Test PostgreSQL connection
kubectl exec -it deployment/recurra -n recurra -- \
  psql -h postgres -U recurra -d recurra

# Test Redis connection
kubectl exec -it deployment/redis -n recurra -- redis-cli ping
```

### High Memory Usage

```bash
# Check memory usage
kubectl top pods -n recurra

# Increase memory limits in deployment.yaml
# Or adjust JVM heap size via JAVA_OPTS
```

### Cache Not Working

```bash
# Check Redis status
kubectl exec -it deployment/redis -n recurra -- redis-cli info

# Check cache statistics
kubectl port-forward svc/recurra 8080:80 -n recurra
curl http://localhost:8080/v1/cache/stats
```

## Production Considerations

### Security

1. **Use image from private registry**:
   ```yaml
   imagePullSecrets:
     - name: registry-credentials
   ```

2. **Enable TLS**:
   - Use cert-manager for automatic certificate management
   - Update ingress with valid TLS certificate

3. **Network Policies**:
   - Restrict pod-to-pod communication
   - Only allow necessary ingress/egress

4. **Pod Security Standards**:
   - Run as non-root user (already configured in Dockerfile)
   - Use read-only root filesystem where possible

### High Availability

1. **Multi-AZ Deployment**:
   ```yaml
   affinity:
     podAntiAffinity:
       requiredDuringSchedulingIgnoredDuringExecution:
         - labelSelector:
             matchExpressions:
               - key: app
                 operator: In
                 values:
                   - recurra
           topologyKey: topology.kubernetes.io/zone
   ```

2. **Database HA**:
   - Use managed PostgreSQL (RDS, Cloud SQL, etc.)
   - Use managed Redis (ElastiCache, MemoryStore, etc.)
   - Or deploy with StatefulSet + replication

### Monitoring & Observability

1. **Prometheus + Grafana**:
   - Deploy Prometheus Operator
   - Create ServiceMonitor for automatic discovery
   - Import Grafana dashboards

2. **Distributed Tracing**:
   - Deploy Jaeger
   - Configure OpenTelemetry exporter

3. **Log Aggregation**:
   - Use Fluentd/Fluent Bit for log collection
   - Forward to Elasticsearch or CloudWatch

### Performance Tuning

1. **JVM Options**:
   ```yaml
   env:
     - name: JAVA_OPTS
       value: "-Xms2g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
   ```

2. **Connection Pooling**:
   - Adjust HikariCP settings in ConfigMap
   - Monitor pool usage via JMX

3. **Cache Tuning**:
   - Adjust Redis maxmemory based on workload
   - Consider Redis Cluster for higher throughput

## Cleanup

```bash
# Delete all resources
kubectl delete -f k8s/

# Delete namespace (removes all resources)
kubectl delete namespace recurra
```

## Support

For issues or questions:
- Check logs: `kubectl logs -f deployment/recurra -n recurra`
- Review events: `kubectl get events -n recurra`
- Consult documentation in `/docs`
