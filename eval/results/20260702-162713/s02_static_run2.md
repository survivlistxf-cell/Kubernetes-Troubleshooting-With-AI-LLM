# s02 | mode=static | run=2 | 18.8s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

## Most likely reasons a pod named **`s02‑imagepull`** stays stuck and how to confirm / remediate each one  

Below the causes are ordered from the most common to the least common for a pod that never gets past the *ImagePull* phase.  For each cause you’ll see:

* **What to look for** (the symptom in the pod’s events or spec)  
* **How to verify it** (kubectl commands you can run when you regain access)  
* **How to fix it** (the change you need to apply)

---

### 1. The image name or tag is wrong / the image does not exist in the registry
**Why it happens** – A typo in the repository, image name, or tag (e.g. `myrepo/app:lates` instead of `latest`) makes the runtime unable to pull the image.

**Verify**  
```bash
kubectl describe pod s02-imagepull -n kubexplain-eval | grep -i "Failed"
```
* Look for events such as `Failed to pull image "myrepo/app:lates"` or `ErrImagePull`.  
* Compare the image reference in the pod spec with what actually exists in the registry (`docker pull <image>` from a workstation).

**Fix**  
* Edit the pod (or the Deployment/StatefulSet that creates it) to use the correct image name/tag:  
```bash
kubectl set image deployment/<owner> <container>=myrepo/app:latest -n kubexplain-eval
```
* If you are using a manifest file, correct the `image:` field and re‑apply it.

---

### 2. Missing or invalid **imagePullSecret** for a private registry
**Why it happens** – The pod tries to pull from a private registry but the secret containing credentials is absent, expired, or not referenced.

**Verify**  
```bash
kubectl get pod s02-imagepull -n kubexplain-eval -o yaml | grep -i imagepullsecrets -A2
```
* If the `imagePullSecrets` list is empty or points to a secret that does not exist, you’ll see `ErrImagePull` with a message like `pull access denied`.

```bash
kubectl get secret <secret-name> -n kubexplain-eval -o yaml
```
* Check that the secret contains a valid `.dockerconfigjson` (or `.dockercfg`) entry.

**Fix**  
* (Re)create the secret with correct credentials:  
```bash
kubectl create secret docker-registry regcred \
  --docker-server=<registry> \
  --docker-username=<user> \
  --docker-password=<pwd> \
  --docker-email=<email> \
  -n kubexplain-eval
```
* Attach the secret to the pod (or to the ServiceAccount used by the pod):  
```bash
kubectl patch serviceaccount default -p '{"imagePullSecrets":[{"name":"regcred"}]}' -n kubexplain-eval
```
* Or add `imagePullSecrets:` directly in the pod spec and redeploy.

---

### 3. Network connectivity from the node to the image registry is broken
**Why it happens** – Firewalls, proxy mis‑configuration, or DNS problems prevent the node’s container runtime from reaching the registry endpoint.

**Verify**  
```bash
kubectl get pod s02-imagepull -n kubexplain-eval -o jsonpath='{.status.hostIP}'
```
* Note the node IP, then SSH (or otherwise access) to that node and run:  
```bash
curl -v https://<registry>/v2/
```
* A timeout or DNS error confirms a connectivity issue.  
* In the pod events you may see `Failed to resolve host` or `i/o timeout`.

**Fix**  
* Ensure the node can resolve the registry hostname (check `/etc/resolv.conf`).  
* Open any required firewall ports (usually TCP 443 for HTTPS registries).  
* If a proxy is required, configure the container runtime (`/etc/docker/daemon.json` or the CRI config) with `HTTP_PROXY/HTTPS_PROXY`.  
* Restart the container runtime after changes.

---

### 4. Registry rate‑limiting or quota exhaustion
**Why it happens** – Public registries (Docker Hub, GCR, ECR, etc.) may throttle anonymous or over‑used accounts, returning HTTP 429.

**Verify**  
* In the pod events you may see `pull rate limit exceeded` or `Too Many Requests`.  
* From a node (or your workstation) try pulling the image manually and watch the HTTP status:  
```bash
docker pull <image>
```
* The output will contain “rate limit exceeded” if that’s the case.

**Fix**  
* Authenticate to the registry (create an `imagePullSecret` with a personal access token).  
* Reduce the number of simultaneous pulls (e.g., stagger deployments).  
* If you control the registry, raise the quota or enable a paid plan.

---

### 5. `imagePullPolicy: Always` forces a pull each time even though the image is already cached
**Why it happens** – With `Always`, the runtime contacts the registry on every pod start. If the registry is temporarily unavailable, the pod will fail even though a good copy exists locally.

**Verify**  
```bash
kubectl get pod s02-imagepull -n kubexplain-eval -o yaml | grep -i imagepullpolicy
```
* If the value is `Always`, the pod will retry a pull on every restart.

**Fix**  
* Change the policy to `IfNotPresent` (or omit it, which defaults to `IfNotPresent` for tagged images):  
```bash
kubectl set imagepullpolicy deployment/<owner> <container>=IfNotPresent -n kubexplain-eval
```
* Redeploy the pod.

---

### 6. Node runs out of disk space for the image layers
**Why it happens** – Pulling a large image fails when the node’s `/var/lib/docker` (or the CRI’s image store) has insufficient free space.

**Verify**  
* From the node, run:  
```bash
df -h /var/lib/docker
```
* Look for < 10 % free.  
* Pod events may show `no space left on device`.

**Fix**  
* Clean up unused images on the node:  
```bash
docker image prune -a
```
* Increase the node’s disk size or add more nodes with sufficient capacity.  
* Consider using a smaller base image to reduce pull size.

---

### 7. Container runtime on the node is unhealthy or mis‑configured
**Why it happens** – The kubelet or Docker/containerd service may be stopped, mis‑configured, or using an incompatible storage driver.

**Verify**  
* Check the node’s condition:  
```bash
kubectl describe node <node-name>
```
* Look for `Ready=False` or `NetworkUnavailable`.  
* SSH to the node and inspect the runtime service status:  
```bash
systemctl status docker   # or containerd
```

**Fix**  
* Restart the runtime: `systemctl restart docker` (or `containerd`).  
* Review the runtime logs for errors and correct any configuration issues (e.g., storage driver, cgroup driver).  
* If the node remains unhealthy, cordon and drain it, then let the scheduler place the pod on a healthy node.

---

### 8. The pod’s ServiceAccount does not have permission to use the secret
**Why it happens** – RBAC rules may prevent the ServiceAccount from reading the `imagePullSecret`.

**Verify**  
```bash
kubectl get rolebinding,clusterrolebinding -n kubexplain-eval | grep <serviceaccount>
```
* Ensure there is a binding that grants `system:serviceaccount:kubexplain-eval:<sa>` the `secret` access.  
* Pod events may show `pull secret not found` with an authorization error.

**Fix**  
* Add a RoleBinding that allows `get, list` on secrets:  
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: pull-secret-access
  namespace: kubexplain-eval
subjects:
- kind: ServiceAccount
  name: <sa>
roleRef:
  kind: Role
  name: secret-reader
  apiGroup: rbac.authorization.k8s.io
```
* Apply the binding and redeploy the pod.

---

## Quick checklist you can run when you regain access

1. `kubectl describe pod s02-imagepull -n kubexplain-eval` – look for **ErrImagePull**, **ImagePullBackOff**, or **Failed** events.  
2. Verify the **image name/tag** and **imagePullPolicy** in the pod spec.  
3. Confirm the **imagePullSecrets** exist and contain valid credentials.  
4. Test node‑to‑registry connectivity (curl / docker pull).  
5. Check node disk space and runtime health.  
6. Review RBAC bindings for the pod’s ServiceAccount.

Address the first failing check you encounter; in most cases the problem is a typo or missing secret, and fixing that will let the pod start. If the issue persists after those steps, move down the list to network, quota, or node‑level problems.
