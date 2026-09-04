output "public_ip" {
  description = "The server's public IP — this is the value AuthLock's own java.rmi.server.hostname/TLS-SAN setup uses (scripts/provision-vm.sh discovers it itself via the EC2 metadata service), and what a remote Swing client connects to (-Dauthlock.server.host=<this>)."
  value       = aws_instance.authlock_server.public_ip
}

output "public_dns" {
  description = "The server's public DNS name — an alternative to public_ip for both java.rmi.server.hostname and the client's -Dauthlock.server.host, and included in the TLS certificate's SAN alongside the IP."
  value       = aws_instance.authlock_server.public_dns
}

output "ssh_command" {
  description = "Copy-paste SSH command to reach the instance."
  value       = "ssh -i /path/to/${var.key_name}.pem ubuntu@${aws_instance.authlock_server.public_ip}"
}

output "next_steps" {
  description = "What to do after `terraform apply` finishes, if you did not set authlock_source_url (the fully-automated path)."
  value       = <<-EOT
    1. Wait ~1-2 minutes for cloud-init to finish (SSH in and check: sudo tail -f /var/log/authlock-provision.log — look for "provision-vm.sh: done").
    2. Copy the project up:
         scp -i /path/to/${var.key_name}.pem -r "AuthLock Project" ubuntu@${aws_instance.authlock_server.public_ip}:/opt/authlock/app
    3. SSH in and run the app-level setup script:
         ssh -i /path/to/${var.key_name}.pem ubuntu@${aws_instance.authlock_server.public_ip}
         sudo /opt/authlock/app/scripts/setup-authlock.sh
    4. From your OWN machine, copy the two files setup-authlock.sh prints the paths to
       (authlock-shared.key and certs/authlock-dev.p12) down from the VM — the client
       needs its own copy of both:
         scp -i /path/to/${var.key_name}.pem ubuntu@${aws_instance.authlock_server.public_ip}:/opt/authlock/app/authlock-shared.key .
         scp -i /path/to/${var.key_name}.pem -r ubuntu@${aws_instance.authlock_server.public_ip}:/opt/authlock/app/certs .
    5. Run the Swing client against the deployed server:
         ./gradlew :authlock-client:run -Dauthlock.server.host=${aws_instance.authlock_server.public_ip}

    See terraform/README.md for the fully-automated path (set authlock_source_url) and troubleshooting.
  EOT
}
