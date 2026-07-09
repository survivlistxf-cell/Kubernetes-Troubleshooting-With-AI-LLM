# s08 | mode=dynamic | run=1 | 7.6s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it?

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

### What’s happening?

* The **container is running** (`State: Running`, `Ready: True`).  
* The **liveness probe** is defined as  

```
http-get http://:8081/ delay=5s timeout=1s period=5s
```

* Nginx (the image you’re using) listens on **port 80**, not on **8081**.  
* When the kubelet runs the probe it tries to reach `http://10.244.226.94:8081/`.  
  The connection is refused, so the probe reports **Unhealthy** and the
  kubelet kills the container:

```
Liveness probe failed: Get "http://10.244.226.94:8081/": dial tcp ... connection refused
Container web failed liveness probe, will be restarted
```

Because the probe never succeeds, the pod is continuously restarted even
though the web server itself works fine.

### How to fix it

#### 1. Point the probe at the correct port (and optionally a path)

If you only need to check that Nginx is up, probe the default HTTP port
(80) and a path that always returns 200 (e.g. `/`).

```bash
kubectl patch pod s08-bad-liveness -n kubexplain-eval \
  -p '{"spec":{"containers":[{"name":"web","livenessProbe":{"httpGet":{"path":"/","port":80}}}]}}'
```

*If the pod is managed by a Deployment, DaemonSet, etc., edit that
controller instead of the pod directly (the pod will be recreated).*

```bash
kubectl edit deployment <deployment-name> -n kubexplain-eval
# locate the container “web” and change:
#   livenessProbe:
#     httpGet:
#       path: /
#       port: 80
```

#### 2. Alternative: expose a health endpoint on 8081

If you really want the probe to stay on 8081, add a side‑car or configure
nginx to listen on that port (e.g. `listen 8081;` in the nginx config)
and expose it via the container.

#### 3. Disable the probe (not recommended)

Only do this if you have another way to detect a stuck container.

```bash
kubectl patch pod s08-bad-liveness -n kubexplain-eval \
  -p '{"spec":{"containers":[{"name":"web","livenessProbe":null}]}}'
```

### Verify the fix

```bash
kubectl describe pod s08-bad-liveness -n kubexplain-eval
```

* Look for the **Liveness** line – it should now show the corrected
  `http-get http://:80/` (or be absent if you disabled it).  
* Watch the events:

```bash
kubectl get pod s08-bad-liveness -n kubexplain-eval -w
```

You should no longer see `Liveness probe failed` warnings, and the
`Restart Count` will stay at 0.
