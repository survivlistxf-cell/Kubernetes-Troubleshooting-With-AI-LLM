# s09 | mode=none | run=3 | 10.2s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it?

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

## What is happening?

| Observation | Meaning |
|-------------|---------|
| **Pod status** – `Ready: False` | The pod is not considered “ready” by the control plane. |
| **Readiness probe** – `http-get http://:80/pagina-inexistenta …` | The probe asks the container for the URL **/pagina‑inexistenta** on port 80. |
| **Events** – `Readiness probe failed: HTTP probe failed with statuscode: 404` | Nginx returns **404 Not Found** for that path, so the probe fails. |
| **Endpoints object** – pod appears under `notReadyAddresses` | Because the pod is not ready, the Service does **not** expose it as a usable endpoint, so no traffic reaches the pod. |

**Root cause:** the readiness probe is checking a URL that does not exist in the default Nginx image, causing the probe to fail repeatedly. As long as the probe fails, the pod stays in the *NotReady* state and is excluded from the Service’s load‑balancing pool.

## How to fix it

You have three typical options:

### 1. Point the probe at a valid path (quickest)

Change the probe’s `path` to something that Nginx serves with a 200 response, e.g. `/` or `/healthz`.

```bash
kubectl patch pod s09-bad-readiness -n kubexplain-eval \
  --type='json' \
  -p='[{"op":"replace","path":"/spec/containers/0/readinessProbe/httpGet/path","value":"/"}]'
```

If the pod is managed by a Deployment/ReplicaSet, patch the controller instead (or edit the manifest and redeploy) so the change survives pod recreation.

### 2. Add a custom endpoint that returns 200

If you want to keep the `/pagina-inexistenta` path, configure Nginx to serve it:

1. Create a ConfigMap with a small `nginx.conf` that adds the location:

   ```yaml
   apiVersion: v1
   kind: ConfigMap
   metadata:
     name: s09-nginx-conf
     namespace: kubexplain-eval
   data:
     default.conf: |
       server {
         listen 80;
         location /pagina-inexistenta {
           return 200 "OK";
         }
         location / {
           root /usr/share/nginx/html;
           index index.html;
         }
       }
   ```

2. Mount the ConfigMap into the pod (update the pod/deployment spec) and reload Nginx.

   ```bash
   kubectl edit deployment <deployment‑name> -n kubexplain-eval
   # add volumeMounts and volumes referencing the ConfigMap, then
   # optionally add an initContainer or command to reload nginx
   ```

After the pod restarts, the probe will receive a 200 and the pod will become ready.

### 3. Use a different probe type (e.g., TCP)

If you only need to know that the container is listening on port 80, a TCP probe is sufficient:

```bash
kubectl patch pod s09-bad-readiness -n kubexplain-eval \
  --type='json' \
  -p='[{"op":"remove","path":"/spec/containers/0/readinessProbe"}]'
```

Then add a TCP probe:

```bash
kubectl patch pod s09-bad-readiness -n kubexplain-eval \
  --type='json' \
  -p='[{"op":"add","path":"/spec/containers/0/readinessProbe","value":{"tcpSocket":{"port":80},"initialDelaySeconds":5,"periodSeconds":5}}]'
```

(Again, apply the change to the owning Deployment/ReplicaSet for persistence.)

## Verify the fix

1. **Check pod readiness**

   ```bash
   kubectl get pod s09-bad-readiness -n kubexplain-eval
   ```

   The `READY` column should become `1/1` and the `STATUS` should show `Running` without the `Ready=False` flag.

2. **Inspect the Service endpoints**

   ```bash
   kubectl get endpoints s09-web -n kubexplain-eval -o yaml
   ```

   The pod’s IP (`10.244.226.98`) should now appear under `addresses` (ready) instead of `notReadyAddresses`.

3. **Test traffic**

   ```bash
   curl http://<service‑clusterIP-or‑DNS>/   # from inside the cluster or via port‑forward
   ```

   You should receive the expected Nginx response.

---

**Bottom line:** the pod is excluded from the Service because its readiness probe is checking a non‑existent URL, resulting in a 404. Adjust the probe to a path that returns 200 (or use a TCP probe) and the pod will become ready, allowing the Service to route traffic to it.
