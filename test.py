from openai import OpenAI
import os

client = OpenAI(
    api_key=os.environ["META_API_KEY"],
    base_url="https://api.meta.ai/v1",
)

# Print the models your key can use
for m in client.models.list():
    print(m.id)
