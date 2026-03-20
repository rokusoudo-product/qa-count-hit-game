terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

locals {
  prefix = "${var.project_name}-${var.environment}"
}

# ── DynamoDB: Rooms ──────────────────────────────────────────
resource "aws_dynamodb_table" "rooms" {
  name         = "${local.prefix}-rooms"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "roomId"

  attribute {
    name = "roomId"
    type = "S"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  tags = { Project = var.project_name, Env = var.environment }
}

# ── DynamoDB: Players ────────────────────────────────────────
resource "aws_dynamodb_table" "players" {
  name         = "${local.prefix}-players"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "roomId"
  range_key    = "playerId"

  attribute {
    name = "roomId"
    type = "S"
  }

  attribute {
    name = "playerId"
    type = "S"
  }

  tags = { Project = var.project_name, Env = var.environment }
}

# ── DynamoDB: Answers ────────────────────────────────────────
resource "aws_dynamodb_table" "answers" {
  name         = "${local.prefix}-answers"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "roomRound"
  range_key    = "playerId"

  attribute {
    name = "roomRound"
    type = "S"
  }

  attribute {
    name = "playerId"
    type = "S"
  }

  tags = { Project = var.project_name, Env = var.environment }
}

# ── DynamoDB: Connections ────────────────────────────────────
resource "aws_dynamodb_table" "connections" {
  name         = "${local.prefix}-connections"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "connectionId"

  attribute {
    name = "connectionId"
    type = "S"
  }

  tags = { Project = var.project_name, Env = var.environment }
}
