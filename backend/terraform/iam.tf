data "aws_iam_policy_document" "lambda_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "lambda_exec" {
  name               = "${local.prefix}-lambda-exec"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

data "aws_iam_policy_document" "lambda_policy" {
  statement {
    sid    = "DynamoDB"
    effect = "Allow"
    actions = [
      "dynamodb:GetItem",
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:DeleteItem",
      "dynamodb:Query",
      "dynamodb:Scan",
    ]
    resources = [
      aws_dynamodb_table.rooms.arn,
      aws_dynamodb_table.players.arn,
      aws_dynamodb_table.answers.arn,
      aws_dynamodb_table.connections.arn,
    ]
  }

  statement {
    sid    = "WebSocketManage"
    effect = "Allow"
    actions = ["execute-api:ManageConnections"]
    resources = ["arn:aws:execute-api:${var.aws_region}:*:${aws_apigatewayv2_api.websocket.id}/*"]
  }

  statement {
    sid    = "Logs"
    effect = "Allow"
    actions = [
      "logs:CreateLogGroup",
      "logs:CreateLogStream",
      "logs:PutLogEvents",
    ]
    resources = ["arn:aws:logs:*:*:*"]
  }
}

resource "aws_iam_role_policy" "lambda_policy" {
  name   = "${local.prefix}-lambda-policy"
  role   = aws_iam_role.lambda_exec.id
  policy = data.aws_iam_policy_document.lambda_policy.json
}
