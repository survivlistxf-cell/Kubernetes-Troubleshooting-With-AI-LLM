# s09 | mode=dynamic | run=1 | 6.8s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it?

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

### What’s happening

* The pod **s09‑bad‑readiness** is scheduled, the container is running, but its **Ready** condition is **False**.  
* The reason is the **readiness probe** that you defined:

```
http-get http://:80/pagina-inexistenta delay=0s timeout=1s period=5s
```

* The probe hits the URL **/pagina‑inexistenta** on port 80. Nginx returns **404**, so the probe fails.  
* Because the pod never becomes *Ready*, the Service **s09‑web** does **not** add the pod to its Endpoints list – it appears under `notReadyAddresses` instead. Consequently the Service has no usable endpoints and receives no traffic.

### How to fix it

1. **Make the probe succeed** – either change the path to one that actually exists (e.g. `/` or any valid page that returns 200) or adjust the probe type.

2. **Or remove/disable the readiness probe** if you don’t need one for this simple nginx pod.

#### Example – edit the pod (or the Deployment/ReplicaSet that created it)

```bash
kubectl edit pod s09-bad-readiness -n kubexplain-eval
```

*Locate the `readinessProbe` section and change it, e.g.:*

```yaml
readinessProbe:
  httpGet:
    path: /
    port: 80
  initialDelaySeconds: 5
  periodSeconds: 10
```

*Save and exit.* The kubelet will re‑run the probe; once it gets a 200 response the pod will become **Ready**, move from `notReadyAddresses` to `addresses`, and the Service will start routing traffic to it.

#### If the pod is managed by a Deployment/ReplicaSet

Patch the controller instead of the individual pod so the change survives recreations:

```bash
kubectl patch deployment <deployment-name> -n kubexplain-eval \
  --type='json' -p='[{"op":"replace","path":"/spec/template/spec/containers/0/readinessProbe/httpGet/path","value":"/"}]'
```

*(Replace `<deployment-name>` with the actual name.)*

Or delete the probe entirely:

```bash
kubectl patch deployment <deployment-name> -n kubexplain-eval \
  --type='json' -p='[{"op":"remove","path":"/spec/template/spec/containers/0/readinessProbe"}]'
```

3. **Verify the fix**

```bash
kubectl get endpoints s09-web -n kubexplain-eval
kubectl describe pod s09-bad-readiness -n kubexplain-eval
```

You should now see the pod listed under `addresses` (ready) and the Service will have a healthy endpoint, allowing traffic to flow.
