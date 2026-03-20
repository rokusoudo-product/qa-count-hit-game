# ── REST API ──────────────────────────────────────────────────
resource "aws_apigatewayv2_api" "rest" {
  name          = "${local.prefix}-rest"
  protocol_type = "HTTP"
  cors_configuration {
    allow_origins = ["*"]
    allow_methods = ["GET", "POST", "OPTIONS"]
    allow_headers = ["Content-Type", "Authorization"]
  }
}

resource "aws_apigatewayv2_stage" "rest" {
  api_id      = aws_apigatewayv2_api.rest.id
  name        = var.environment
  auto_deploy = true
}

# POST /rooms
resource "aws_apigatewayv2_integration" "create_room" {
  api_id                 = aws_apigatewayv2_api.rest.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.create_room.invoke_arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_route" "create_room" {
  api_id    = aws_apigatewayv2_api.rest.id
  route_key = "POST /rooms"
  target    = "integrations/${aws_apigatewayv2_integration.create_room.id}"
}

resource "aws_lambda_permission" "create_room" {
  statement_id  = "AllowAPIGateway"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.create_room.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.rest.execution_arn}/*/*"
}

# GET /rooms/{roomId}/qr
resource "aws_apigatewayv2_integration" "get_qr" {
  api_id                 = aws_apigatewayv2_api.rest.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.get_qr.invoke_arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_route" "get_qr" {
  api_id    = aws_apigatewayv2_api.rest.id
  route_key = "GET /rooms/{roomId}/qr"
  target    = "integrations/${aws_apigatewayv2_integration.get_qr.id}"
}

resource "aws_lambda_permission" "get_qr" {
  statement_id  = "AllowAPIGateway"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.get_qr.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.rest.execution_arn}/*/*"
}

# POST /rooms/{roomId}/join
resource "aws_apigatewayv2_integration" "join_room" {
  api_id                 = aws_apigatewayv2_api.rest.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.join_room.invoke_arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_route" "join_room" {
  api_id    = aws_apigatewayv2_api.rest.id
  route_key = "POST /rooms/{roomId}/join"
  target    = "integrations/${aws_apigatewayv2_integration.join_room.id}"
}

resource "aws_lambda_permission" "join_room" {
  statement_id  = "AllowAPIGateway"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.join_room.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.rest.execution_arn}/*/*"
}

# ── WebSocket API ─────────────────────────────────────────────
resource "aws_apigatewayv2_api" "websocket" {
  name                       = "${local.prefix}-ws"
  protocol_type              = "WEBSOCKET"
  route_selection_expression = "$request.body.action"
}

resource "aws_apigatewayv2_stage" "websocket" {
  api_id      = aws_apigatewayv2_api.websocket.id
  name        = var.environment
  auto_deploy = true
}

# $connect
resource "aws_apigatewayv2_integration" "ws_connect" {
  api_id           = aws_apigatewayv2_api.websocket.id
  integration_type = "AWS_PROXY"
  integration_uri  = aws_lambda_function.ws_connect.invoke_arn
}

resource "aws_apigatewayv2_route" "ws_connect" {
  api_id    = aws_apigatewayv2_api.websocket.id
  route_key = "$connect"
  target    = "integrations/${aws_apigatewayv2_integration.ws_connect.id}"
}

resource "aws_lambda_permission" "ws_connect" {
  statement_id  = "AllowAPIGateway"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.ws_connect.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.websocket.execution_arn}/*/*"
}

# $disconnect
resource "aws_apigatewayv2_integration" "ws_disconnect" {
  api_id           = aws_apigatewayv2_api.websocket.id
  integration_type = "AWS_PROXY"
  integration_uri  = aws_lambda_function.ws_disconnect.invoke_arn
}

resource "aws_apigatewayv2_route" "ws_disconnect" {
  api_id    = aws_apigatewayv2_api.websocket.id
  route_key = "$disconnect"
  target    = "integrations/${aws_apigatewayv2_integration.ws_disconnect.id}"
}

resource "aws_lambda_permission" "ws_disconnect" {
  statement_id  = "AllowAPIGateway"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.ws_disconnect.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.websocket.execution_arn}/*/*"
}
