export interface Message {
    text: string;
    sender: 'user' | 'bot';
    timestamp: Date;
}
