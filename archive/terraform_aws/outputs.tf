output "rest_api_url" {
  description = "REST API endpoint URL"
  value       = "${aws_apigatewayv2_stage.rest.invoke_url}"
}

output "websocket_url" {
  description = "WebSocket endpoint URL"
  value       = "${aws_apigatewayv2_stage.websocket.invoke_url}"
}

output "rooms_table_name" {
  value = aws_dynamodb_table.rooms.name
}

output "players_table_name" {
  value = aws_dynamodb_table.players.name
}

output "answers_table_name" {
  value = aws_dynamodb_table.answers.name
}
