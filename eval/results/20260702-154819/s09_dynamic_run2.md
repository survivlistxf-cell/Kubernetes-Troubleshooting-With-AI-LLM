# s09 | mode=dynamic | run=2 | 7.4s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it?

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

### What’s happening

* The pod **s09‑bad‑readiness** is running, but its **Ready** condition is **False**.  
* The readiness probe defined for the container is:

```yaml
http-get http://:80/pagina-inexistenta delay=0s timeout=1s period=5s #success=1 #failure=3
```

* The probe is hitting the path **/pagina-inexistenta** on the Nginx container.  
* Nginx returns **404**, so the probe fails (`Readiness probe failed: HTTP probe failed with statuscode: 404`).  

Because the probe never succeeds, the pod is placed in the **notReadyAddresses** list of the `s09-web` Endpoints object. A Service only routes traffic to **ready** endpoints, so the Service has no usable back‑ends and receives no traffic.

### Root cause

**Readiness probe is mis‑configured – it checks a URL that does not exist, causing the pod to stay “NotReady”.**  

The Service selector (`app=s09-web`) matches the pod, but the pod never becomes a ready endpoint.

### How to fix it

1. **Choose a valid health‑check path** (e.g. `/` or any URL that returns HTTP 200).  
2. **Update the pod (or its Deployment/ReplicaSet) spec** with the corrected probe, or remove the probe if you don’t need one.

#### Example – edit the pod (or the owning controller)

```bash
kubectl edit pod s09-bad-readiness -n kubexplain-eval
```

Change the `readinessProbe` section to something like:

```yaml
readinessProbe:
  httpGet:
    path: /
    port: 80
  initialDelaySeconds: 5
  periodSeconds: 10
  timeoutSeconds: 2
  successThreshold: 1
  failureThreshold: 3
```

*If the pod is managed by a Deployment, edit the Deployment instead:*

```bash
kubectl edit deployment <deployment-name> -n kubexplain-eval
```

and adjust the same `readinessProbe` block.

3. **Apply the change** (the edit command saves and applies automatically). The pod will be restarted, the readiness probe will start succeeding, and the pod will move from `notReadyAddresses` to the `addresses` list of the `s09-web` Endpoints object.

4. **Verify**:

```bash
kubectl get endpoints s09-web -n kubexplain-eval
```

You should now see the pod IP under `addresses` (no `notReadyAddresses`).  

```bash
kubectl get svc s09-web -n kubexplain-eval
```

and a request to the Service IP/ClusterIP should be routed to the pod.

### Quick checklist

- [ ] Probe path exists and returns **200**.  
- [ ] `initialDelaySeconds` / `timeoutSeconds` are generous enough for the container to start.  
- [ ] After the change, `kubectl get pod s09-bad-readiness -o wide` shows `READY 1/1`.  
- [ ] `kubectl get endpoints s09-web` shows the pod under `addresses` (no `notReadyAddresses`).  

Once the readiness probe succeeds, the Service will have a healthy endpoint and traffic will flow normally.
