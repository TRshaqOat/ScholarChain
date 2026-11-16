# Terraform plan summary

- Plan SHA-256: `2955206c828fb7a8f39d276d9c8122695cbc7e234d9fac7b862da9b821796011`
- Commit: `3e0983e6a0c78fa8b8c771a6c7147b60508cfcae`
- Account: `834519512655`
- Region: `us-east-2`
- runtime_enabled: `false`
- Var file: `infra/terraform/envs/course/course.tfvars`
- Created: 2026-11-16T05:39:05Z
- Counts: {"add":43,"change":0,"destroy":0}

## Stateful destroy or replace (needs explicit human sign-off)

none

## IAM changes

- create module.build.aws_iam_role.codebuild
- create module.build.aws_iam_role_policy.codebuild
- create module.emr.aws_iam_instance_profile.instance
- create module.emr.aws_iam_role.instance
- create module.emr.aws_iam_role.service
- create module.emr.aws_iam_role_policy.instance_s3
- create module.emr.aws_iam_role_policy.service_pass_role
- create module.emr.aws_iam_role_policy_attachment.service_v2

## High-cost resources

- create module.build.aws_codebuild_project.services_image
