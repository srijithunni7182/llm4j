# Deploying GetViral to Google Cloud Run

GetViral runs as one stateless container. Everything that must survive lives in managed services:

| What | Where |
|---|---|
| Accounts, runs, live events, questions, sessions, memory, voice samples | **Cloud SQL for PostgreSQL** |
| Generated images and Reels | **Cloud Storage** (private bucket, served through the app's ownership check) |
| Keys and client secrets | **Secret Manager** |
| The container image | **Artifact Registry** |

Any instance can serve any request, because sessions and the live event stream are in Postgres. That also
makes instance restarts and scale-out safe. Before you deploy, check the image locally with
`docker compose up --build` (see the README).

## 1. One-time setup

```bash
export PROJECT=my-project REGION=us-central1 SERVICE=getviral
gcloud config set project $PROJECT

gcloud services enable run.googleapis.com sqladmin.googleapis.com artifactregistry.googleapis.com \
  cloudbuild.googleapis.com secretmanager.googleapis.com storage.googleapis.com

# Image registry
gcloud artifacts repositories create getviral --repository-format=docker --location=$REGION

# Runtime identity
gcloud iam service-accounts create getviral-run
SA=getviral-run@$PROJECT.iam.gserviceaccount.com

# Database (pick a larger tier for real traffic; each instance opens up to GETVIRAL_DB_POOL connections)
gcloud sql instances create getviral-db --database-version=POSTGRES_16 --region=$REGION \
  --tier=db-custom-1-3840 --storage-auto-increase
gcloud sql databases create getviral --instance=getviral-db
DB_PASSWORD=$(openssl rand -base64 24)
gcloud sql users create getviral --instance=getviral-db --password="$DB_PASSWORD"
gcloud projects add-iam-policy-binding $PROJECT --member=serviceAccount:$SA --role=roles/cloudsql.client

# Media bucket (private; the app streams files to their owners)
gcloud storage buckets create gs://$PROJECT-getviral-media --location=$REGION --uniform-bucket-level-access
gcloud storage buckets add-iam-policy-binding gs://$PROJECT-getviral-media \
  --member=serviceAccount:$SA --role=roles/storage.objectAdmin

# Secrets
printf %s "$DB_PASSWORD"        | gcloud secrets create getviral-db-password --data-file=-
openssl rand -base64 32 | tr -d '\n' | gcloud secrets create getviral-token-key --data-file=-
printf %s "$GEMINI_API_KEY"     | gcloud secrets create getviral-gemini-key --data-file=-
printf %s "$GOOGLE_CLIENT_SECRET" | gcloud secrets create getviral-google-secret --data-file=-
for s in getviral-db-password getviral-token-key getviral-gemini-key getviral-google-secret; do
  gcloud secrets add-iam-policy-binding $s --member=serviceAccount:$SA --role=roles/secretmanager.secretAccessor
done
```

Keep `getviral-token-key` safe and never rotate it casually. It encrypts every creator's social tokens,
and if you lose it, everyone has to reconnect their accounts.

## 2. First deploy

Build and push the image from the **repository root** (it compiles the llm4j stack from source):

```bash
gcloud builds submit --config examples/getviral/cloudbuild.yaml .
```

The build's last step deploys the new image. On the very first run the service doesn't exist yet, so if
that step fails, create the service with its full configuration:

```bash
IMAGE=$REGION-docker.pkg.dev/$PROJECT/getviral/getviral:latest
URL=https://getviral.example.com          # or the run.app URL once you know it

gcloud run deploy $SERVICE --image=$IMAGE --region=$REGION --service-account=$SA \
  --add-cloudsql-instances=$PROJECT:$REGION:getviral-db \
  --cpu=2 --memory=4Gi --no-cpu-throttling --min-instances=1 --max-instances=4 \
  --concurrency=80 --timeout=3600 --allow-unauthenticated \
  --set-env-vars="GETVIRAL_DB_URL=jdbc:postgresql:///getviral?cloudSqlInstance=$PROJECT:$REGION:getviral-db&socketFactory=com.google.cloud.sql.postgres.SocketFactory" \
  --set-env-vars="GETVIRAL_DB_USER=getviral,GETVIRAL_GCS_BUCKET=$PROJECT-getviral-media" \
  --set-env-vars="GETVIRAL_PUBLIC_URL=$URL,GETVIRAL_SECURE_COOKIES=true,GETVIRAL_MODE=gemini" \
  --set-env-vars="GOOGLE_CLIENT_ID=<your-oauth-client-id>" \
  --set-secrets="GETVIRAL_DB_PASSWORD=getviral-db-password:latest,GETVIRAL_TOKEN_KEY=getviral-token-key:latest" \
  --set-secrets="GEMINI_API_KEY=getviral-gemini-key:latest,GOOGLE_CLIENT_SECRET=getviral-google-secret:latest"
```

Why these flags:

- **`--no-cpu-throttling` and `--min-instances=1`.** A pack runs in background workers after the HTTP
  request that started it has returned. With the default throttled CPU, those workers would stall.
- **`--timeout=3600`.** The live view is a server-sent-events stream that stays open for the whole run.
  Clients reconnect automatically and resume from the last event.
- **`--memory=4Gi`.** It covers the bundled embedding model, image work and H.264 encoding. Each instance
  runs up to `GETVIRAL_WORKERS` (default 8) packs at once.
- **Instance replacement is safe.** Every step of a run is journaled in Postgres. A run waiting for its
  creator holds nothing, and a run whose instance is replaced mid-way is resumed by another instance from
  its last recorded step (after `GETVIRAL_STALE_AFTER` of silence, default 10 minutes; given up after two
  resumes; a run given up on doesn't count against the creator's quota).

Every later release is just `gcloud builds submit --config examples/getviral/cloudbuild.yaml .`, which only
swaps the image and keeps all of the settings above.

On Cloud Run the app **refuses to start** if it finds laptop settings: dev login enabled, no Google sign-in,
no token key, no media bucket, a non-PostgreSQL database, or a non-https public URL. Check the revision's
logs for the exact list.

## 3. OAuth apps

The redirect URIs are built from `GETVIRAL_PUBLIC_URL`:

| Provider | Redirect URI | Scopes / notes |
|---|---|---|
| Google sign-in (Google Cloud console → Credentials → OAuth client, "Web application") | `$URL/login/oauth2/code/google` | `openid email profile` |
| YouTube (same Google client, or `YOUTUBE_CLIENT_ID/SECRET`) | `$URL/connect/youtube/callback` | `youtube.readonly`, which needs Google's verification for public use |
| Instagram (Meta app → Instagram → API setup with Instagram Login) | `$URL/connect/instagram/callback` | `instagram_business_basic`, `instagram_business_content_publish`; needs Meta App Review; creators need a professional account |
| X (developer portal → OAuth 2.0, confidential client) | `$URL/connect/x/callback` | `tweet.read users.read offline.access` |

Put the client IDs in env vars (`INSTAGRAM_APP_ID`, `X_CLIENT_ID`, …) and the secrets in Secret Manager
(`INSTAGRAM_APP_SECRET`, `X_CLIENT_SECRET`, …). A connection whose app isn't configured shows up as
"Not set up on this server yet" in onboarding, and the rest of the product still works.

## 4. Operating it

- **Health.** Cloud Run can probe `/actuator/health/liveness` and `/actuator/health/readiness`.
- **Cost controls.** Set the monthly pack quota per creator with `GETVIRAL_PACKS_PER_MONTH` and the
  concurrency per instance with `GETVIRAL_WORKERS`. Google Search grounding and Gemini image generation are
  billed to the platform key beyond their free tiers; `GETVIRAL_IMAGE_PROVIDER=pollinations` avoids the
  image cost.
- **Schema changes** are Flyway migrations and run automatically on startup.
- **Custom domain.** Map it with `gcloud run domain-mappings create` (or a load balancer), then update
  `GETVIRAL_PUBLIC_URL` and the OAuth redirect URIs to match.
