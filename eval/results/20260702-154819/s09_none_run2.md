# s09 | mode=none | run=2 | 6.5s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it?

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

### What is happening?

* The pod **s09‑bad‑readiness** is **Running**, but its **Ready** condition is **False**.  
* The readiness probe defined for the container is  

```yaml
http-get http://:80/pagina-inexistenta delay=0s timeout=1s period=5s #success=1 #failure=3
```

* The probe is hitting the path **/pagina‑inexistenta** on port **80**.  
* Nginx (the image you are using) returns **404** for that URL, which the kubelet reports as:

```
Readiness probe failed: HTTP probe failed with statuscode: 404
```

* Because the probe never succeeds, the pod is placed in the **notReadyAddresses** list of the **s09‑web** Service. Consequently the Service has **no ready endpoints**, so no traffic is routed to the pod.

### Root cause

The readiness probe is pointing at a URL that does **not exist** (or the application is not configured to serve it). As long as the probe keeps failing, the pod will never become “Ready” and will be excluded from the Service’s endpoint list.

### How to fix it

1. **Choose a path that actually returns 2xx** (e.g. `/` or any health‑check endpoint your app provides).  
2. **Update the pod (or its owning Deployment/ReplicaSet) with the corrected probe**.  
3. **Apply the change** so the pod is recreated (or the probe is re‑evaluated) and becomes Ready.

#### Example: patch the pod (if it is a standalone pod)

```bash
kubectl patch pod s09-bad-readiness -n kubexplain-eval \
  --type='json' -p='[
    {"op":"replace","path":"/spec/containers/0/readinessProbe/httpGet/path","value":"/"}
  ]'
```

#### Example: edit the manifest (if the pod is managed by a Deployment)

```bash
kubectl edit deployment <deployment-name> -n kubexplain-eval
```

*Locate the `readinessProbe` section and change the `path` from `/pagina-inexistenta` to a valid endpoint, e.g.:*

```yaml
readinessProbe:
  httpGet:
    path: /
    port: 80
  initialDelaySeconds: 0
  periodSeconds: 5
  timeoutSeconds: 1
  successThreshold: 1
  failureThreshold: 3
```

4. **Save the edit** – the Deployment will roll out a new replica with the corrected probe.  
5. Verify that the pod becomes Ready and that the Service now has a ready endpoint:

```bash
kubectl get pod s09-bad-readiness -n kubexplain-eval
kubectl get endpoints s09-web -n kubexplain-eval
```

You should see `READY` = `1/1` for the pod and the endpoint listed under `addresses` (not `notReadyAddresses`).

### Quick sanity check

If you prefer to **remove the probe altogether** (not recommended for production, but useful for debugging), you can delete the `readinessProbe` block from the container spec and re‑apply. The pod will immediately be considered Ready (provided the container is listening on the advertised port).

---

**Bottom line:** the pod is excluded from the Service because its readiness probe is failing on a non‑existent URL. Fix the probe to point at a valid path (or remove it), and the Service will start routing traffic to the pod.
