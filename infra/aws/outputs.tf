output "hosts" {
  description = "name => { role, index, public_ip, private_ip }"
  value = {
    for name, instance in aws_instance.host : name => {
      role       = local.hosts[name].role
      index      = local.hosts[name].index
      public_ip  = instance.public_ip
      private_ip = instance.private_ip
    }
  }
}

output "ssh_key_path" {
  value = local_sensitive_file.ssh_key.filename
}
