# Course environment values. No secrets and no personal data: the API key is set in Secrets Manager
# by the human after apply, and the alarm email is passed as TF_VAR_alarm_email at plan time.

environment     = "course"
owner           = "scholarchain-human-architect"
expiration_date = "2026-12-15"

# Cloud window 1 (D-13): network, data bucket, EMR roles and groups, ECR, CodeBuild, log groups.
# Set to true for cloud window 2, together with image_tag and the three served runs below.
runtime_enabled = false

availability_zones      = ["us-east-2a", "us-east-2b"]
vpc_cidr                = "10.40.0.0/16"
alb_ingress_cidr_blocks = ["0.0.0.0/0"]

# Keep false until the instructor approves the evidence archive (spec Cleanup requirements).
data_bucket_force_destroy = false

logs_expiration_days               = 30
artifacts_expiration_days          = 90
noncurrent_version_expiration_days = 7
log_retention_days                 = 14

emr_release_label       = "emr-spark-8.0.0"
emr_instance_type       = "m6g.xlarge"
emr_core_instance_count = 2

dynamodb_table_prefix           = "scholarchain-course-"
dynamodb_deletion_protection    = false
dynamodb_point_in_time_recovery = false

neptune_engine_version         = "1.4.8.1"
neptune_parameter_group_family = "neptune1.4"
neptune_instance_class         = "db.t4g.medium"
neptune_deletion_protection    = false
neptune_skip_final_snapshot    = true

secret_recovery_window_days = 7

# Served runtime inputs (cloud window 2). image_tag is the 40-hex Git SHA that scripts/package.sh
# built; the run IDs name the uploaded store-conversion and embedding runs. Leave null in window 1.
# image_tag           = "<40-hex git sha>"
# runtime_graph_run   = "run-<32 hex>"
# runtime_items_run   = "run-<32 hex>"
# runtime_vectors_run = "run-<32 hex>"
runtime_snapshot   = "2026-10-03"
window_summary_run = "run-6fbad498beb0d8f570df67665fb97544"

embedding_model_id         = "amazon.titan-embed-text-v2:0"
haiku_inference_profile_id = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
haiku_destination_regions  = ["us-east-1", "us-east-2", "us-west-2"]

# query-api plus worker maximums x llm_requests_per_minute <= 8 Haiku calls per minute.
service_max_counts = {
  query_api          = 1
  graph_evidence_api = 2
  worker             = 1
}
haiku_calls_per_minute_cap = 8
llm_requests_per_minute    = 4
