variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "ap-northeast-1"
}

variable "project_name" {
  description = "Project name prefix"
  type        = string
  default     = "hitokazu"
}

variable "environment" {
  description = "Environment (dev/prod)"
  type        = string
  default     = "dev"
}

variable "room_ttl_hours" {
  description = "Room TTL in hours"
  type        = number
  default     = 24
}
