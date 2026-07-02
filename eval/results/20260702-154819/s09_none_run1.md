# s09 | mode=none | run=1 | 6.8s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it?

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

### What is happening?

* The pod **s09‑bad‑readiness** is **Running**, but its **Ready** condition is **False**.  
* The reason is the **readiness probe** defined for the container:

```
http-get http://:80/pagina-inexistenta delay=0s timeout=1s period=5s #success=1 #failure=3
```

* When the kubelet runs the probe it receives **HTTP 404**:

```
Readiness probe failed: HTTP probe failed with statuscode: 404
```

* Because the probe never succeeds, the pod is never marked **Ready**.  
* The Service **s09‑web** therefore lists the pod only under **notReadyAddresses**, and no ready endpoints are available → the service cannot route any traffic to the pod.

### Root cause

The readiness probe is checking a URL (`/pagina-inexistenta`) that does **not exist** in the Nginx container, so the probe always fails with 404. In Kubernetes a failing readiness probe keeps the pod out of the Service’s endpoint list.

### How to fix it

You have three typical options:

1. **Change the probe to a path that returns 200** (e.g. the default Nginx landing page `/` or any custom health‑check endpoint you add).

2. **Remove or disable the readiness probe** if you don’t need one for this simple pod.

3. **Add a handler for the requested path** inside the container (e.g. create a file `pagina-inexistenta.html` that returns 200) so the existing probe succeeds.

Below are the most common ways to apply each option.

---

#### 1. Update the readiness probe to a valid path

If the pod is created by a Deployment/ReplicaSet, edit the pod template:

```bash
kubectl edit deployment s09-bad-readiness -n kubexplain-eval
```

Locate the `readinessProbe` section and change the `path` (or `httpGet` URL) to something that exists, for example:

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

Save and exit – the Deployment will roll out a new pod (or update the existing one) and the probe will start succeeding, making the pod **Ready** and adding it to the Service endpoints.

---

#### 2. Remove the readiness probe entirely

If you decide you don’t need a readiness check:

```bash
kubectl edit deployment s09-bad-readiness -n kubexplain-eval
```

Delete the whole `readinessProbe:` block from the container spec, then save. The pod will be considered Ready as soon as its containers start.

---

#### 3. Provide the requested file so the probe succeeds

Create a simple HTML file inside the container that matches the probe path:

```bash
kubectl exec -it s09-bad-readiness -n kubexplain-eval -- /bin/sh -c "echo '<h1>OK</h1>' > /usr/share/nginx/html/pagina-inexistenta.html"
```

Then reload Nginx (or simply restart the pod) so the file is served:

```bash
kubectl delete pod s09-bad-readiness -n kubexplain-eval
```

When the pod restarts, the `/pagina-inexistenta` URL will return **200**, the readiness probe will pass, and the pod will become Ready.

---

### Verify the fix

After applying one of the changes, wait a few seconds and then check:

```bash
kubectl get pod s09-bad-readiness -n kubexplain-eval
kubectl get endpoints s09-web -n kubexplain-eval
```

You should see:

* `READY` column showing `1/1` (or `READY` condition `True`).
* The Service’s endpoints list the pod under `addresses` (not `notReadyAddresses`).

Once the pod is Ready, traffic sent to the Service `s09-web` will be routed to it.
