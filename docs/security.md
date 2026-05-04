# Segurança no OrderFlow

## Filosofia

Segurança é tratada como preocupação transversal, considerada desde a fase de design e validada continuamente no ciclo de vida do código. Não é uma camada adicionada ao final, nem uma checklist preenchida antes do go-live. É princípio constante: defesa em profundidade, mínimo privilégio, falhar de forma segura, jamais confiar em entrada não validada, jamais expor o que não precisa ser exposto.

O modelo de ameaças do OrderFlow é desenvolvido seguindo STRIDE — Spoofing, Tampering, Repudiation, Information Disclosure, Denial of Service, Elevation of Privilege — e atualizado a cada mudança arquitetural significativa. Cada ameaça é mapeada a um controle ou aceita explicitamente como risco residual com justificativa.

## Autenticação

Autenticação é gerenciada centralmente via Keycloak, atuando como Identity Provider OAuth2/OIDC. Clientes autenticam-se contra o Keycloak e recebem JWTs assinados com RS256.

Os tokens têm TTL curto (15 minutos para access tokens), forçando renovação frequente. Refresh tokens têm TTL maior (8 horas) mas são rotacionados a cada uso — refresh consumido invalida-se imediatamente. Chaves de assinatura são rotacionadas mensalmente, com período de overlap onde duas chaves são aceitas simultaneamente para permitir migração suave.

Os serviços validam tokens recebidos verificando assinatura (chaves públicas obtidas via JWKS endpoint do Keycloak, com cache local), expiração, audience, issuer e claims customizadas. Tokens inválidos resultam em 401 imediato sem acesso a qualquer recurso.

Cliente-a-serviço usa OAuth2 Authorization Code flow com PKCE para aplicações públicas. Serviço-a-serviço usa Client Credentials flow com certificados ao invés de secrets compartilhados quando possível.

## Autorização

Autorização é separada de autenticação e aplicada em camadas. O API Gateway aplica autorização coarse-grained — bloqueia requisições sem token válido ou sem o role básico necessário para chamar a rota. Cada serviço aplica autorização fine-grained — verifica que o usuário pode realizar aquela operação naquele recurso específico.

Autorização é baseada em roles (RBAC) combinados com atributos (ABAC) quando políticas mais sofisticadas são necessárias. Por exemplo, "um cliente pode ver seus próprios pedidos mas não os de outros" é uma regra ABAC: a autorização depende de relação entre identidade do usuário e atributo do recurso.

A política é codificada em código, não em configuração externa, para preservar testabilidade e versionamento junto com a aplicação.

## Comunicação Segura

Todo tráfego externo é HTTPS apenas, terminando TLS 1.3 no API Gateway. Certificados são gerenciados via cert-manager no Kubernetes, com renovação automática.

Tráfego interno entre serviços usa mTLS através do service mesh Istio. Cada pod recebe um certificado X.509 de uma CA interna, com identidade derivada do ServiceAccount Kubernetes. Isto garante que apenas serviços identificados podem se comunicar entre si — qualquer pod sem certificado válido é bloqueado pelo proxy Envoy.

Políticas de rede no nível Kubernetes (NetworkPolicies) restringem comunicação ao mínimo necessário: o serviço de Order pode falar com PostgreSQL e Kafka; não pode falar diretamente com o serviço de Payment (toda comunicação inter-serviço passa por Kafka).

## Gerenciamento de Segredos

Segredos jamais existem em código, configuração estática, ou variáveis de ambiente plain-text em produção. HashiCorp Vault é o cofre central, com autenticação via ServiceAccount Kubernetes (auth method `kubernetes`).

Aplicações obtêm segredos em runtime através do Spring Cloud Vault. Segredos têm TTL e são automaticamente renovados antes da expiração. Para credenciais de banco de dados, dynamic secrets são usados quando suportado: Vault gera credenciais únicas por instância de aplicação, com TTL curto, revogadas automaticamente — comprometimento de uma instância expõe um segredo de vida limitada e localizada.

Para desenvolvimento local, secrets de teste estão em arquivos `.env.local` git-ignored. CI usa GitHub Actions Secrets, isolados por ambiente.

## Validação de Entrada

Toda entrada externa é validada na fronteira do serviço, antes de qualquer processamento. Spring's Bean Validation (Jakarta Validation) é aplicado em todos os DTOs de entrada, com constraints declarativos para tipos básicos. Validações específicas de domínio (regra de negócio sobre o input) são implementadas como validators customizados ou aplicadas no agregado.

A regra é jamais confiar em dados externos. JWTs são verificados independente do gateway já ter feito. Headers são tratados como hostis. Tamanhos máximos são impostos para prevenir ataques de exaustão de memória.

Acesso a SQL usa exclusivamente queries parametrizadas via R2DBC ou Spring Data — nunca concatenação de strings em SQL. Acesso a NoSQL usa typed queries do Spring Data MongoDB, prevenindo injection no nível de query.

Sanitização de saída quando dados vão para HTML (via templates Thymeleaf com auto-escape habilitado), prevenindo XSS reflexivo ou armazenado.

## Proteção de Dados Sensíveis

Dados pessoalmente identificáveis (PII) são identificados e classificados explicitamente no modelo: nome, email, CPF, endereço, dados de pagamento. Cada classe sensível tem tratamento específico.

Em repouso, PII é criptografado no nível de coluna no PostgreSQL usando pgcrypto, com chaves gerenciadas pelo Vault e rotacionadas anualmente. MongoDB usa encryption-at-rest no nível de cluster (AWS managed key) para um baseline e field-level encryption para campos especialmente sensíveis. Backups são criptografados no S3 com KMS.

Em trânsito, todas as conexões usam TLS — nenhum tráfego em clear-text dentro nem fora do cluster.

Em logs, PII é tokenizada ou redacted. Frameworks de logging têm patterns customizados que detectam padrões PII (CPF, email, número de cartão) e os substituem por hashes ou tokens. Cards são jamais logados — Payment Service apenas armazena tokens retornados pelo gateway, não dados PCI.

LGPD compliance é garantida através de operações administrativas para anonimização e exportação de dados de cliente sob demanda. Eventos antigos podem ser "scrubbed" (PII removida mantendo a estrutura) após período de retenção legal.

## Auditoria

Toda ação significativa produz um evento de auditoria, gravado num log append-only separado dos logs de aplicação. Eventos incluem: quem (identidade autenticada), o quê (operação realizada), quando (timestamp UTC), de onde (IP de origem), para qual recurso, com qual resultado.

Logs de auditoria são imutáveis — append-only no S3 com Object Lock para garantia legal. Acessá-los requer permissão separada da operação. Operações administrativas (mudar configuração, ler dados de cliente sem ser para cumprir uma operação solicitada) geram logs detalhados.

A auditoria não é só compliance — também é forense. Em incidente de segurança, os logs permitem reconstruir o que aconteceu, quem fez, e o blast radius.

## Segurança no Pipeline

A segurança no pipeline CI/CD ataca múltiplas frentes. Análise estática com SonarQube, com regras de segurança específicas (uso indevido de crypto, deserialização insegura, paths injection). Análise de dependências com Trivy e Dependabot, alertando vulnerabilidades em libraries. Container scanning com Trivy nas imagens construídas, falhando o build em vulnerabilidades críticas.

Análise dinâmica (DAST) com OWASP ZAP rodando contra um ambiente staging, executando suite OWASP Top 10 a cada release. SAST integrado em IDE via plugins — feedback imediato durante desenvolvimento.

Imagens Docker usam base images mínimas (distroless ou Alpine) para reduzir superfície de ataque. Não há shell ou ferramentas de debug em imagens de produção. Containers rodam como usuário não-root com filesystem read-only sempre que possível.

## Hardening do Kubernetes

Pods têm SecurityContext explícito: run as non-root, read-only root filesystem, no privilege escalation, capabilities mínimas. NetworkPolicies restringem tráfego ao mínimo necessário. PodSecurityStandards (restricted level) são aplicados no namespace.

Acesso ao cluster usa RBAC com least-privilege: cada ServiceAccount tem permissões mínimas; humanos autenticam via SSO com MFA obrigatório; logs de acesso são monitorados.

Secrets do Kubernetes são apenas referência ao Vault; o ETCD não armazena secrets em texto claro.

## Resposta a Incidentes

Existe um runbook de resposta a incidentes documentado e exercitado. Tipos de incidente típicos têm playbooks específicos: vazamento de credencial, comprometimento de pod, ataque DDoS, exfiltração suspeita de dados.

Gates de detecção incluem alerts em padrões anômalos de tráfego, falhas de autenticação em volume incomum, acessos a dados em horários atípicos, falhas de TLS handshake. SIEM agrega logs e correlaciona sinais de ameaça em tempo quase real.

Em caso de incidente confirmado, as etapas seguem o framework: contenção (isolar o componente afetado), erradicação (remover a causa raiz), recuperação (retornar ao estado normal), e lessons learned (post-mortem público interno).

Drills de incidente são realizados trimestralmente — simulações de cenários de ameaça com a equipe respondendo como em produção real. Identificam gaps de processo, ferramentas e treinamento antes de o incidente real acontecer.

## Compliance

A arquitetura de segurança é desenhada para suportar compliance com LGPD (Brasil), GDPR (UE quando aplicável), PCI-DSS Level 1 (no contexto de pagamento, indiretamente — usamos gateways certificados que minimizam nosso escopo), e SOC 2 Type II.

Documentação de controles, evidências de execução, e auditoria interna são mantidas continuamente, não retroativamente. Auditorias externas são facilitadas porque a evidência está sempre disponível.

## Treinamento e Cultura

Segurança é responsabilidade de todos os engenheiros, não exclusiva de uma equipe de SecOps. Treinamento em segurança é parte do onboarding e é renovado anualmente. Threat modeling é parte do design review de features sensíveis. Bug bounty interno encoraja desenvolvedores a relatar vulnerabilidades suspeitas sem medo.

A medida última do sucesso é cultural: a equipe pensa em segurança naturalmente como parte do design, não como burocracia imposta de fora.
