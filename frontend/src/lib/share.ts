/**
 * Sharing to WhatsApp, where the players already are.
 *
 * A wa.me link with the text filled in opens WhatsApp on a phone (or WhatsApp Web on a computer) with the message
 * ready, and the person picks the chat or group to send it to. Nothing is sent by the app itself.
 */
export function whatsAppUrl(text: string): string {
  return `https://wa.me/?text=${encodeURIComponent(text)}`;
}

/** Opens WhatsApp with the message ready to send. */
export function shareOnWhatsApp(text: string): void {
  window.open(whatsAppUrl(text), '_blank', 'noopener,noreferrer');
}
